package com.graphify.impact;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;
import com.graphify.indexer.model.UsageKind;
import com.graphify.store.Chunks;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@link ImpactGraph} over Oracle; every IN list is split at Oracle's 1000-item limit. */
@Repository
public class JdbcImpactGraph implements ImpactGraph {

    private final JdbcTemplate jdbc;

    public JdbcImpactGraph(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Long, ImpactSymbol> symbols(Collection<Long> ids) {
        Map<Long, ImpactSymbol> found = new LinkedHashMap<>();
        for (List<Long> chunk : chunks(ids)) {
            jdbc.query("SELECT id, symbol_key, kind, display_signature, class_fqn, parent_id, origin, name_only FROM symbol "
                    + "WHERE id IN ("
                    + Chunks.placeholders(chunk.size()) + ") ORDER BY id", rs -> {
                        long id = rs.getLong("id");
                        long parent = rs.getLong("parent_id");
                        Long parentId = rs.wasNull() ? null : parent;
                        found.put(id, new ImpactSymbol(id, rs.getString("symbol_key"),
                                SymbolKind.valueOf(rs.getString("kind")), rs.getString("display_signature"),
                                rs.getString("class_fqn"), parentId, SymbolOrigin.valueOf(rs.getString("origin")),
                                rs.getInt("name_only") == 1));
                    }, chunk.toArray());
        }
        return found;
    }

    @Override
    public List<Long> descendants(long typeId) {
        return jdbc.queryForList("SELECT id FROM symbol START WITH parent_id = ? CONNECT BY PRIOR id = parent_id "
                + "ORDER SIBLINGS BY id", Long.class, typeId);
    }

    @Override
    public Map<String, Long> idsOfKeys(Collection<String> keys) {
        Map<String, Long> found = new LinkedHashMap<>();
        for (List<String> chunk : Chunks.of(List.copyOf(new LinkedHashSet<>(keys)), Chunks.MAX_IN_LIST)) {
            jdbc.query("SELECT id, symbol_key FROM symbol WHERE symbol_key IN (" + Chunks.placeholders(chunk.size())
                    + ") ORDER BY id", rs -> {
                        found.putIfAbsent(rs.getString("symbol_key"), rs.getLong("id"));
                    }, chunk.toArray());
        }
        return found;
    }

    @Override
    public List<ImpactUsage> usagesTo(Collection<Long> targetIds, Set<UsageKind> kinds, Set<Confidence> confidences) {
        if (targetIds.isEmpty() || kinds.isEmpty() || confidences.isEmpty()) {
            return List.of();
        }
        List<ImpactUsage> found = new ArrayList<>();
        for (List<Long> chunk : chunks(targetIds)) {
            List<Object> args = new ArrayList<>(chunk);
            kinds.forEach(k -> args.add(k.name()));
            confidences.forEach(c -> args.add(c.name()));
            found.addAll(jdbc.query("""
                    SELECT id, from_symbol_id, to_symbol_id, kind, confidence, module_id
                      FROM usage
                     WHERE to_symbol_id IN (%s) AND kind IN (%s) AND confidence IN (%s)
                     ORDER BY id
                    """.formatted(Chunks.placeholders(chunk.size()), Chunks.placeholders(kinds.size()),
                            Chunks.placeholders(confidences.size())),
                    (rs, row) -> new ImpactUsage(rs.getLong("id"), rs.getLong("from_symbol_id"),
                            rs.getLong("to_symbol_id"), UsageKind.valueOf(rs.getString("kind")),
                            Confidence.valueOf(rs.getString("confidence")), rs.getLong("module_id")),
                    args.toArray()));
        }
        return found;
    }

    @Override
    public Map<Long, UsageDetail> usageDetails(Collection<Long> usageIds) {
        Map<Long, UsageDetail> found = new LinkedHashMap<>();
        for (List<Long> chunk : chunks(usageIds)) {
            jdbc.query("SELECT id, file_path, line_no, column_no, snippet FROM usage WHERE id IN ("
                    + Chunks.placeholders(chunk.size()) + ") ORDER BY id", rs -> {
                        found.put(rs.getLong("id"), new UsageDetail(rs.getString("file_path"), rs.getInt("line_no"),
                                rs.getInt("column_no"), rs.getString("snippet")));
                    }, chunk.toArray());
        }
        return found;
    }

    @Override
    public Map<Long, List<Long>> overriddenMethods(Collection<Long> symbolIds) {
        Map<Long, List<Long>> found = new LinkedHashMap<>();
        for (List<Long> chunk : chunks(symbolIds)) {
            jdbc.query("SELECT from_symbol_id, to_symbol_id FROM usage WHERE kind = 'OVERRIDES' AND from_symbol_id IN ("
                    + Chunks.placeholders(chunk.size()) + ") ORDER BY id", rs -> {
                        found.computeIfAbsent(rs.getLong("from_symbol_id"), k -> new ArrayList<>())
                                .add(rs.getLong("to_symbol_id"));
                    }, chunk.toArray());
        }
        return found;
    }

    @Override
    public Map<Long, List<Long>> overriders(Collection<Long> methodIds) {
        Map<Long, List<Long>> found = new LinkedHashMap<>();
        for (List<Long> chunk : chunks(methodIds)) {
            jdbc.query("SELECT from_symbol_id, to_symbol_id FROM usage WHERE kind = 'OVERRIDES' AND to_symbol_id IN ("
                    + Chunks.placeholders(chunk.size()) + ") ORDER BY id", rs -> {
                        found.computeIfAbsent(rs.getLong("to_symbol_id"), k -> new ArrayList<>())
                                .add(rs.getLong("from_symbol_id"));
                    }, chunk.toArray());
        }
        return found;
    }

    @Override
    public List<AnnotationUse> annotationsOn(Collection<Long> symbolIds, Collection<String> annotationKeys) {
        if (symbolIds.isEmpty() || annotationKeys.isEmpty()) {
            return List.of();
        }
        List<String> keys = List.copyOf(new LinkedHashSet<>(annotationKeys));
        List<AnnotationUse> found = new ArrayList<>();
        for (List<Long> ids : chunks(symbolIds)) {
            for (List<String> keyChunk : Chunks.of(keys, Chunks.MAX_IN_LIST)) {
                List<Object> args = new ArrayList<>(ids);
                args.addAll(keyChunk);
                found.addAll(jdbc.query("""
                        SELECT u.from_symbol_id, t.symbol_key, u.snippet, u.module_id
                          FROM usage u JOIN symbol t ON t.id = u.to_symbol_id
                         WHERE u.kind = 'ANNOTATION' AND u.from_symbol_id IN (%s) AND t.symbol_key IN (%s)
                         ORDER BY u.id
                        """.formatted(Chunks.placeholders(ids.size()), Chunks.placeholders(keyChunk.size())),
                        (rs, row) -> new AnnotationUse(rs.getLong("from_symbol_id"), rs.getString("symbol_key"),
                                rs.getString("snippet"), rs.getLong("module_id")),
                        args.toArray()));
            }
        }
        return found;
    }

    @Override
    public Map<Long, List<Long>> declaringModules(Collection<Long> symbolIds) {
        Map<Long, List<Long>> found = new LinkedHashMap<>();
        for (List<Long> chunk : chunks(symbolIds)) {
            jdbc.query("SELECT DISTINCT symbol_id, module_id FROM symbol_declaration WHERE symbol_id IN ("
                    + Chunks.placeholders(chunk.size()) + ") ORDER BY symbol_id, module_id", rs -> {
                        found.computeIfAbsent(rs.getLong("symbol_id"), k -> new ArrayList<>())
                                .add(rs.getLong("module_id"));
                    }, chunk.toArray());
        }
        return found;
    }

    @Override
    public Map<Long, RepoState> repoStates(Collection<Long> moduleIds) {
        Map<Long, RepoState> found = new LinkedHashMap<>();
        for (List<Long> chunk : chunks(moduleIds)) {
            jdbc.query("""
                    SELECT m.id AS module_id, r.id AS repo_id, r.project_key, r.slug, m.path, m.classpath_mode,
                           r.last_indexed_commit, r.last_indexed_at
                      FROM maven_module m JOIN scm_repository r ON r.id = m.repo_id
                     WHERE m.id IN (%s) ORDER BY m.id
                    """.formatted(Chunks.placeholders(chunk.size())), rs -> {
                        OffsetDateTime at = rs.getObject("last_indexed_at", OffsetDateTime.class);
                        found.put(rs.getLong("module_id"), new RepoState(rs.getLong("module_id"), rs.getLong("repo_id"),
                                rs.getString("project_key"), rs.getString("slug"), rs.getString("path"), rs.getString("classpath_mode"),
                                rs.getString("last_indexed_commit"), at == null ? null : at.toInstant()));
                    }, chunk.toArray());
        }
        return found;
    }

    private static List<List<Long>> chunks(Collection<Long> ids) {
        return Chunks.of(List.copyOf(new LinkedHashSet<>(ids)), Chunks.MAX_IN_LIST);
    }
}
