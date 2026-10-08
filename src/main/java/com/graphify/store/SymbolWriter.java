package com.graphify.store;

import com.graphify.common.util.Utf8;
import com.graphify.indexer.model.Symbol;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes the symbols one repository's index refers to. Symbols are shared by every repository, so a row is only
 * changed when the incoming symbol ranks higher: name-only (0) &lt; BINARY (1) &lt; SOURCE (2). A SOURCE row is also
 * refreshed by another SOURCE write whose kind, class, member or display differs; an unchanged row is not locked.
 */
@Component
class SymbolWriter {

    private static final String MERGE = """
            MERGE INTO symbol s
            USING (SELECT ? AS symbol_key, ? AS kind, ? AS class_fqn, ? AS member_name, ? AS display_signature,
                          ? AS origin, ? AS name_only FROM dual) n
            ON (s.symbol_key = n.symbol_key)
            WHEN MATCHED THEN UPDATE SET s.kind = n.kind, s.class_fqn = n.class_fqn, s.member_name = n.member_name,
                    s.display_signature = n.display_signature, s.origin = n.origin, s.name_only = n.name_only
                WHERE (CASE WHEN n.name_only = 1 THEN 0 WHEN n.origin = 'BINARY' THEN 1 ELSE 2 END)
                    > (CASE WHEN s.name_only = 1 THEN 0 WHEN s.origin = 'BINARY' THEN 1 ELSE 2 END)
                   OR (n.name_only = 0 AND n.origin = 'SOURCE' AND s.name_only = 0 AND s.origin = 'SOURCE'
                       AND (s.kind <> n.kind OR s.display_signature <> n.display_signature
                            OR NVL(s.member_name, ' ') <> NVL(n.member_name, ' ') OR s.class_fqn <> n.class_fqn))
            WHEN NOT MATCHED THEN INSERT (symbol_key, kind, class_fqn, member_name, display_signature, origin, name_only)
                VALUES (n.symbol_key, n.kind, n.class_fqn, n.member_name, n.display_signature, n.origin, n.name_only)
            """;

    private static final String LINK_PARENT = "UPDATE symbol SET parent_id = ? WHERE id = ? AND parent_id IS NULL";

    record Result(Map<String, Long> ids, Set<String> skippedKeys) {
    }

    private final JdbcTemplate jdbc;

    SymbolWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Merges the symbols in key order, then links each member to its parent.
     *
     * <p>Two repositories inserting the same new key race on the unique index: the loser waits for the winner's
     * transaction and then gets ORA-00001, and its retried MERGE sees the committed row. Each ORA-00001 therefore
     * means another writer's transaction has committed, so a chunk can fail at most once per other concurrent
     * writer. A chunk is attempted at most {@code maxConcurrentWriters + 1} times; a further failure is rethrown.
     *
     * <p>The MERGE phase takes its row locks in key order, so concurrent MERGEs cannot deadlock each other. Linking
     * parents is a second pass over rows another writer may also be linking, so a deadlock (ORA-00060) is possible,
     * though rare. The caller's transaction then rolls back cleanly; plan 4 retries the whole
     * {@code RepositoryIndexWriter.replace()} once.
     *
     * @param maxConcurrentWriters how many index writes can run at once ({@code index.parallelism})
     */
    Result upsert(List<Symbol> symbols, int batchSize, int maxConcurrentWriters) {
        List<Symbol> storable = new ArrayList<>();
        Set<String> skipped = new LinkedHashSet<>();
        for (Symbol symbol : symbols) {
            if (StoreLimits.storable(symbol)) {
                storable.add(symbol);
            } else {
                skipped.add(symbol.key());
            }
        }
        storable.sort(Comparator.comparing(Symbol::key));
        for (List<Symbol> chunk : Chunks.of(storable, batchSize)) {
            mergeWithRetry(chunk, maxConcurrentWriters + 1);
        }
        Map<String, Long> ids = ids(storable.stream().map(Symbol::key).toList());
        linkParents(storable, ids, batchSize);
        return new Result(ids, skipped);
    }

    private void mergeWithRetry(List<Symbol> chunk, int maxAttempts) {
        List<Object[]> rows = chunk.stream().map(SymbolWriter::row).toList();
        for (int attempt = 1; ; attempt++) {
            try {
                jdbc.batchUpdate(MERGE, rows);
                return;
            } catch (DuplicateKeyException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
            }
        }
    }

    private static Object[] row(Symbol symbol) {
        String display = symbol.displaySignature() == null || symbol.displaySignature().isBlank()
                ? symbol.key() : symbol.displaySignature();
        return new Object[] {
                symbol.key(),
                symbol.kind().name(),
                symbol.classFqn(),
                symbol.memberName(),
                Utf8.truncateToBytes(display, StoreLimits.DISPLAY_SIGNATURE_BYTES),
                symbol.origin().name(),
                symbol.nameOnly() ? 1 : 0};
    }

    private Map<String, Long> ids(List<String> keys) {
        Map<String, Long> ids = new HashMap<>(keys.size() * 2);
        for (List<String> chunk : Chunks.of(keys, Chunks.MAX_IN_LIST)) {
            String placeholders = Chunks.placeholders(chunk.size());
            jdbc.query("SELECT id, symbol_key FROM symbol WHERE symbol_key IN (" + placeholders + ")",
                    rs -> {
                        ids.put(rs.getString("symbol_key"), rs.getLong("id"));
                    },
                    chunk.toArray());
        }
        return ids;
    }

    private void linkParents(List<Symbol> symbols, Map<String, Long> ids, int batchSize) {
        List<Object[]> links = new ArrayList<>();
        for (Symbol symbol : symbols) {
            Long parent = symbol.parentKey() == null ? null : ids.get(symbol.parentKey());
            Long self = ids.get(symbol.key());
            if (parent != null && self != null) {
                links.add(new Object[] {parent, self});
            }
        }
        for (List<Object[]> chunk : Chunks.of(links, batchSize)) {
            jdbc.batchUpdate(LINK_PARENT, chunk);
        }
    }
}
