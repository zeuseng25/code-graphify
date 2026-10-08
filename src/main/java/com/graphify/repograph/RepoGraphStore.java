package com.graphify.repograph;

import com.graphify.store.Chunks;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The stored repository graph analyses (spec §9.3); replaced as a whole after each index. */
@Repository
public class RepoGraphStore {

    private final JdbcTemplate jdbc;

    public RepoGraphStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void replace(long repositoryId, String commit, ClassGraph graph, RepoGraphAnalyzer.Analysis analysis,
            int batchSize) {
        clear(repositoryId);
        List<Object[]> metrics = new ArrayList<>();
        List<Object[]> communities = new ArrayList<>();
        analysis.metrics().forEach((fqn, m) -> {
            long symbolId = graph.classes().get(fqn).symbolId();
            metrics.add(new Object[] {repositoryId, symbolId, m.inDegree(), m.outDegree(), m.dependents(),
                    m.entryPoint() ? 1 : 0});
            communities.add(new Object[] {repositoryId, commit, symbolId, m.communityId(), m.communityLabel()});
        });
        List<Object[]> cycles = new ArrayList<>();
        for (int i = 0; i < analysis.cycles().size(); i++) {
            for (String packageName : analysis.cycles().get(i)) {
                cycles.add(new Object[] {repositoryId, i + 1, packageName});
            }
        }
        for (List<Object[]> chunk : Chunks.of(metrics, batchSize)) {
            jdbc.batchUpdate("INSERT INTO repo_graph_metric (repo_id, symbol_id, in_degree, out_degree, dependents, "
                    + "is_entry_point) VALUES (?, ?, ?, ?, ?, ?)", chunk);
        }
        for (List<Object[]> chunk : Chunks.of(communities, batchSize)) {
            jdbc.batchUpdate("INSERT INTO repo_graph_community (repo_id, commit_hash, symbol_id, community_id, "
                    + "community_label) VALUES (?, ?, ?, ?, ?)", chunk);
        }
        for (List<Object[]> chunk : Chunks.of(cycles, batchSize)) {
            jdbc.batchUpdate("INSERT INTO repo_graph_cycle (repo_id, cycle_id, package_name) VALUES (?, ?, ?)", chunk);
        }
        long communityCount = analysis.metrics().values().stream().map(ClassMetrics::communityId).distinct().count();
        jdbc.update("INSERT INTO repo_graph_analysis (repo_id, commit_hash, class_count, community_count, cycle_count) "
                + "VALUES (?, ?, ?, ?, ?)", repositoryId, commit, analysis.metrics().size(), communityCount,
                analysis.cycles().size());
    }

    @Transactional
    public void clear(long repositoryId) {
        // keep the graph table list in sync with RepositoryIndexWriter.remove
        for (String table : List.of("repo_graph_cycle", "repo_graph_community", "repo_graph_metric",
                "repo_graph_analysis")) {
            jdbc.update("DELETE FROM " + table + " WHERE repo_id = ?", repositoryId);
        }
    }

    public Optional<AnalysisState> state(long repositoryId) {
        return jdbc.query("SELECT commit_hash, analyzed_at, class_count, community_count, cycle_count "
                + "FROM repo_graph_analysis WHERE repo_id = ?", (rs, row) -> new AnalysisState(
                        rs.getString("commit_hash"), rs.getObject("analyzed_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("class_count"), rs.getInt("community_count"), rs.getInt("cycle_count")),
                repositoryId).stream().findFirst();
    }

    public Map<Long, ClassMetrics> metrics(long repositoryId) {
        Map<Long, ClassMetrics> metrics = new HashMap<>();
        jdbc.query("""
                SELECT m.symbol_id, m.in_degree, m.out_degree, m.dependents, m.is_entry_point, c.community_id,
                       c.community_label
                  FROM repo_graph_metric m
                  LEFT JOIN repo_graph_community c ON c.repo_id = m.repo_id AND c.symbol_id = m.symbol_id
                 WHERE m.repo_id = ?
                """, rs -> {
                    metrics.put(rs.getLong("symbol_id"), new ClassMetrics(rs.getInt("in_degree"),
                            rs.getInt("out_degree"), rs.getInt("dependents"), rs.getInt("is_entry_point") == 1,
                            rs.getObject("community_id", Integer.class), rs.getString("community_label")));
                }, repositoryId);
        return metrics;
    }

    public List<CriticalClass> critical(long repositoryId, int limit) {
        return jdbc.query("""
                SELECT m.symbol_id, s.symbol_key, m.in_degree, m.out_degree, m.dependents, m.is_entry_point
                  FROM repo_graph_metric m JOIN symbol s ON s.id = m.symbol_id
                 WHERE m.repo_id = ?
                 ORDER BY m.dependents DESC, m.in_degree DESC, s.symbol_key
                 FETCH FIRST ? ROWS ONLY
                """, (rs, row) -> new CriticalClass(rs.getLong("symbol_id"), rs.getString("symbol_key"),
                        rs.getInt("in_degree"), rs.getInt("out_degree"), rs.getInt("dependents"),
                        rs.getInt("is_entry_point") == 1), repositoryId, limit);
    }

    public List<CommunitySummary> communities(long repositoryId, int limit) {
        return jdbc.query("""
                SELECT community_id, MAX(community_label) AS label, COUNT(*) AS members
                  FROM repo_graph_community WHERE repo_id = ?
                 GROUP BY community_id ORDER BY community_id
                 FETCH FIRST ? ROWS ONLY
                """, (rs, row) -> new CommunitySummary(rs.getInt("community_id"), rs.getString("label"),
                        rs.getInt("members")), repositoryId, limit);
    }

    public List<PackageCycle> cycles(long repositoryId) {
        Map<Integer, List<String>> cycles = new LinkedHashMap<>();
        jdbc.query("SELECT cycle_id, package_name FROM repo_graph_cycle WHERE repo_id = ? ORDER BY cycle_id, "
                + "package_name", rs -> {
                    cycles.computeIfAbsent(rs.getInt("cycle_id"), id -> new ArrayList<>())
                            .add(rs.getString("package_name"));
                }, repositoryId);
        return cycles.entrySet().stream().map(e -> new PackageCycle(e.getKey(), List.copyOf(e.getValue()))).toList();
    }

    public int entryPointCount(long repositoryId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM repo_graph_metric WHERE repo_id = ? "
                + "AND is_entry_point = 1", Integer.class, repositoryId);
        return count == null ? 0 : count;
    }

    public List<String> entryPointClasses(long repositoryId, int limit) {
        return jdbc.queryForList("""
                SELECT s.symbol_key FROM repo_graph_metric m JOIN symbol s ON s.id = m.symbol_id
                 WHERE m.repo_id = ? AND m.is_entry_point = 1
                 ORDER BY s.symbol_key FETCH FIRST ? ROWS ONLY
                """, String.class, repositoryId, limit);
    }
}
