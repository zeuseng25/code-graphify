package com.graphify.store;

import static com.graphify.store.StoreFixtures.symbol;
import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.indexer.model.Symbol;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class SymbolWriterTest extends OracleIntegrationTest {

    @Autowired
    SymbolWriter writer;

    @Autowired
    TransactionTemplate transactions;

    @BeforeEach
    void clean() {
        StoreFixtures.cleanIndexTables(jdbc);
    }

    private String kindAndOrigin(String key) {
        return jdbc.queryForObject("SELECT kind || ':' || origin || ':' || name_only FROM symbol WHERE symbol_key = ?",
                String.class, key);
    }

    @Test
    void insertsSymbolsAndReturnsTheirIds() {
        SymbolWriter.Result result = writer.upsert(List.of(
                symbol("p.A", SymbolKind.CLASS, SymbolOrigin.SOURCE, false),
                symbol("p.A#run()", SymbolKind.METHOD, SymbolOrigin.SOURCE, false)), 10, 1);

        assertThat(result.ids()).containsOnlyKeys("p.A", "p.A#run()");
        assertThat(result.skippedKeys()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT p.symbol_key FROM symbol c JOIN symbol p ON c.parent_id = p.id "
                + "WHERE c.symbol_key = 'p.A#run()'", String.class)).isEqualTo("p.A");
    }

    @Test
    void higherRankReplacesLowerButNeverTheReverse() {
        writer.upsert(List.of(
                symbol("p.Guess", SymbolKind.CLASS, SymbolOrigin.BINARY, true),
                symbol("p.Lib", SymbolKind.CLASS, SymbolOrigin.BINARY, false),
                symbol("p.Own", SymbolKind.INTERFACE, SymbolOrigin.SOURCE, false)), 10, 1);

        writer.upsert(List.of(
                symbol("p.Guess", SymbolKind.INTERFACE, SymbolOrigin.BINARY, false),
                symbol("p.Lib", SymbolKind.ENUM, SymbolOrigin.SOURCE, false),
                symbol("p.Own", SymbolKind.CLASS, SymbolOrigin.BINARY, false)), 10, 1);
        writer.upsert(List.of(symbol("p.Lib", SymbolKind.CLASS, SymbolOrigin.BINARY, true)), 10, 1);

        assertThat(kindAndOrigin("p.Guess")).isEqualTo("INTERFACE:BINARY:0");
        assertThat(kindAndOrigin("p.Lib")).isEqualTo("ENUM:SOURCE:0");
        assertThat(kindAndOrigin("p.Own")).isEqualTo("INTERFACE:SOURCE:0");
    }

    @Test
    void equalRankSourceRewriteRefreshesKindButBinaryStillDoesNot() {
        writer.upsert(List.of(symbol("p.T", SymbolKind.CLASS, SymbolOrigin.SOURCE, false)), 10, 1);
        writer.upsert(List.of(symbol("p.T", SymbolKind.INTERFACE, SymbolOrigin.SOURCE, false)), 10, 1);

        assertThat(kindAndOrigin("p.T")).isEqualTo("INTERFACE:SOURCE:0");

        writer.upsert(List.of(symbol("p.T", SymbolKind.ENUM, SymbolOrigin.BINARY, false)), 10, 1);

        assertThat(kindAndOrigin("p.T")).isEqualTo("INTERFACE:SOURCE:0");
    }

    @Test
    void skipsSymbolsThatDoNotFitTheColumnsAndCountsThem() {
        String longKey = "p.A#m(" + "x".repeat(4100) + ")";

        SymbolWriter.Result result = writer.upsert(List.of(
                symbol("p.A", SymbolKind.CLASS, SymbolOrigin.SOURCE, false),
                symbol(longKey, SymbolKind.METHOD, SymbolOrigin.SOURCE, false)), 10, 1);

        assertThat(result.skippedKeys()).containsExactly(longKey);
        assertThat(result.ids()).containsOnlyKeys("p.A");
    }

    @Test
    void handlesMoreSymbolsThanOneBatchOrOneInList() {
        List<Symbol> many = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            many.add(symbol("p.C" + i, SymbolKind.CLASS, SymbolOrigin.BINARY, false));
        }

        SymbolWriter.Result result = writer.upsert(many, 700, 1);

        assertThat(result.ids()).hasSize(2500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM symbol", Integer.class)).isEqualTo(2500);
    }

    @Test
    void concurrentWritersOfTheSameNewSymbolsBothSucceed() throws Exception {
        List<Symbol> shared = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            shared.add(symbol("java.lang.Shared" + i, SymbolKind.CLASS, SymbolOrigin.BINARY, false));
        }
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Integer> write = () -> transactions.execute(status -> {
            await(start);
            return writer.upsert(shared, 200, 2).ids().size();
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(write);
            Future<Integer> second = pool.submit(write);
            assertThat(first.get()).isEqualTo(1500);
            assertThat(second.get()).isEqualTo(1500);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM symbol", Integer.class)).isEqualTo(1500);
    }

    @Test
    void fourConcurrentWritersOfOverlappingNewSymbolsAllSucceed() throws Exception {
        int writers = 4;
        int privateKeys = 300;
        CyclicBarrier start = new CyclicBarrier(writers);
        List<Callable<Integer>> writes = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            List<Symbol> keys = new ArrayList<>();
            for (int i = 0; i < 1200; i++) {
                keys.add(symbol(String.format("k%04d", i), SymbolKind.CLASS, SymbolOrigin.BINARY, false));
            }
            for (int i = 0; i < privateKeys; i++) {
                keys.add(symbol(String.format("w%d-%04d", w, i), SymbolKind.CLASS, SymbolOrigin.BINARY, false));
            }
            writes.add(() -> transactions.execute(status -> {
                await(start);
                return writer.upsert(keys, 100, writers).ids().size();
            }));
        }

        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            for (Future<Integer> result : pool.invokeAll(writes)) {
                assertThat(result.get()).isEqualTo(1200 + privateKeys);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM symbol", Integer.class))
                .isEqualTo(1200 + writers * privateKeys);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
