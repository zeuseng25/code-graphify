package com.graphify.impact;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.SymbolOrigin;
import com.graphify.indexer.model.UsageKind;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcImpactGraphTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @Autowired
    JdbcImpactGraph graph;

    @TempDir
    static Path work;

    @BeforeAll
    void load() throws Exception {
        ShopFixture.load(jdbc, writer, work);
    }

    private long id(String key) {
        return ShopFixture.symbolId(jdbc, key);
    }

    @Test
    void readsSymbolsDescendantsAndKeys() {
        long gateway = id("com.shop.lib.PaymentGateway");

        assertThat(graph.symbols(List.of(gateway)).get(gateway)).satisfies(symbol -> {
            assertThat(symbol.classFqn()).isEqualTo("com.shop.lib.PaymentGateway");
            assertThat(symbol.origin()).isEqualTo(SymbolOrigin.SOURCE);
            assertThat(symbol.nameOnly()).isFalse();
        });
        long twin = id("com.shop.lib.PriceFormatter#format/1");
        assertThat(graph.symbols(List.of(twin)).get(twin).nameOnly()).isTrue();
        assertThat(graph.descendants(gateway)).containsExactly(id("com.shop.lib.PaymentGateway#charge(int)"));
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            keys.add("no.such.Key" + i);
        }
        keys.add("com.shop.lib.PriceFormatter#format/1");
        assertThat(graph.idsOfKeys(keys))
                .containsExactly(Map.entry("com.shop.lib.PriceFormatter#format/1", id("com.shop.lib.PriceFormatter#format/1")));
    }

    @Test
    void readsUsagesOverridesAnnotationsAndRepoStates() {
        long charge = id("com.shop.lib.PaymentGateway#charge(int)");
        long card = id("com.shop.api.CardGateway#charge(int)");
        long post = id("com.shop.api.OrderController#checkout()");

        List<ImpactUsage> usages = graph.usagesTo(List.of(charge), EnumSet.of(UsageKind.CALL),
                EnumSet.allOf(Confidence.class));
        assertThat(usages).singleElement().satisfies(u -> assertThat(u.fromId())
                .isEqualTo(id("com.shop.api.CheckoutService#checkout(int)")));
        assertThat(graph.overriddenMethods(List.of(card))).containsEntry(card, List.of(charge));
        assertThat(graph.overriders(List.of(charge))).containsExactly(Map.entry(charge, List.of(card)));
        assertThat(graph.annotationsOn(List.of(post), List.of("org.springframework.web.bind.annotation.PostMapping")))
                .singleElement().satisfies(a -> assertThat(a.snippet()).startsWith("@PostMapping"));
        long module = usages.getFirst().moduleId();
        assertThat(graph.declaringModules(List.of(post))).containsExactly(Map.entry(post, List.of(module)));
        assertThat(graph.repoStates(List.of(module)).get(module))
                .satisfies(state -> {
                    assertThat(state.repository()).isEqualTo("shop-api");
                    assertThat(state.projectKey()).isEqualTo("TEST");
                    assertThat(state.repositoryId()).isEqualTo(ShopFixture.repositoryId(jdbc, "shop-api"));
                    assertThat(state.classpathMode()).isEqualTo("FULL");
                    assertThat(state.lastIndexedCommit()).isEqualTo("api-1");
                    assertThat(state.lastIndexedAt()).isNotNull();
                });
    }

    @Test
    void readsUsageDetailsSeparately() {
        long format = id("com.shop.lib.PriceFormatter#format(int)");
        ImpactUsage usage = graph.usagesTo(List.of(format), EnumSet.of(UsageKind.CALL), EnumSet.allOf(Confidence.class))
                .getFirst();
        List<Long> ids = new ArrayList<>();
        for (long i = 0; i < 1500; i++) {
            ids.add(-i - 1);
        }
        ids.add(usage.id());

        Map<Long, UsageDetail> details = graph.usageDetails(ids);

        assertThat(details).containsOnlyKeys(usage.id());
        assertThat(details.get(usage.id())).satisfies(d -> {
            assertThat(d.filePath()).endsWith("CheckoutService.java");
            assertThat(d.line()).isPositive();
            assertThat(d.column()).isPositive();
            assertThat(d.snippet()).isEqualTo("return formatter.format(cents);");
        });
    }

    @Test
    void usagesToHandlesMoreThanAThousandTargets() {
        List<Long> targets = new ArrayList<>();
        for (long i = 0; i < 1500; i++) {
            targets.add(-i - 1);
        }
        targets.add(id("com.shop.lib.PaymentGateway#charge(int)"));

        assertThat(graph.usagesTo(targets, EnumSet.allOf(UsageKind.class), EnumSet.allOf(Confidence.class)))
                .hasSize(2);
        assertThat(graph.symbols(targets)).hasSize(1);
    }
}
