package com.graphify.search;

import com.graphify.api.Page;
import com.graphify.api.Paging;
import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;
import com.graphify.indexer.model.UsageKind;
import com.graphify.repository.RepositoryRef;
import com.graphify.store.Chunks;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SymbolDetails {

    private static final String REF_COLUMNS = "s.id, s.symbol_key, s.kind, s.display_signature";

    private final JdbcTemplate jdbc;

    public SymbolDetails(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A cheap check for endpoints that only need the symbol to exist. */
    public boolean exists(long id) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM symbol WHERE id = ?", Long.class, id);
        return count != null && count > 0;
    }

    public Optional<SymbolDetail> find(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + REF_COLUMNS + ", s.origin, s.name_only, s.parent_id FROM symbol s WHERE s.id = ?", id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        SymbolRef self = new SymbolRef(id, (String) row.get("SYMBOL_KEY"), SymbolKind.valueOf((String) row.get("KIND")),
                (String) row.get("DISPLAY_SIGNATURE"));
        Number parentId = (Number) row.get("PARENT_ID");
        SymbolRef parent = parentId == null ? null
                : refs("SELECT " + REF_COLUMNS + " FROM symbol s WHERE s.id = ?", parentId.longValue()).getFirst();
        List<DeclarationView> declarations = jdbc.query("""
                SELECT r.id AS repo_id, r.project_key, r.slug, m.path, d.file_path, d.line_no
                  FROM symbol_declaration d JOIN maven_module m ON m.id = d.module_id
                  JOIN scm_repository r ON r.id = m.repo_id
                 WHERE d.symbol_id = ? ORDER BY r.project_key, r.slug, r.id, m.path, d.file_path, d.line_no
                """, (rs, n) -> new DeclarationView(repository(rs), rs.getString("path"), rs.getString("file_path"),
                        rs.getInt("line_no")), id);
        return Optional.of(new SymbolDetail(
                self,
                SymbolOrigin.valueOf((String) row.get("ORIGIN")),
                ((Number) row.get("NAME_ONLY")).intValue() == 1,
                parent,
                declarations,
                refs("SELECT " + REF_COLUMNS + " FROM symbol s WHERE s.parent_id = ? ORDER BY s.symbol_key", id),
                related("u.from_symbol_id = ? AND u.kind = 'OVERRIDES'", "u.to_symbol_id", id),
                related("u.to_symbol_id = ? AND u.kind = 'OVERRIDES'", "u.from_symbol_id", id),
                related("u.from_symbol_id = ? AND u.kind IN ('EXTENDS', 'IMPLEMENTS')", "u.to_symbol_id", id),
                related("u.to_symbol_id = ? AND u.kind IN ('EXTENDS', 'IMPLEMENTS')", "u.from_symbol_id", id)));
    }

    public Page<UsageView> usages(long id, Set<Confidence> confidences, Set<UsageKind> kinds, Long repositoryId,
            Paging paging) {
        StringBuilder where = new StringBuilder(" WHERE u.to_symbol_id = ?");
        List<Object> args = new ArrayList<>(List.of(id));
        if (confidences != null && !confidences.isEmpty()) {
            where.append(" AND u.confidence IN (").append(Chunks.placeholders(confidences.size())).append(')');
            confidences.forEach(c -> args.add(c.name()));
        }
        if (kinds != null && !kinds.isEmpty()) {
            where.append(" AND u.kind IN (").append(Chunks.placeholders(kinds.size())).append(')');
            kinds.forEach(k -> args.add(k.name()));
        }
        if (repositoryId != null) {
            where.append(" AND r.id = ?");
            args.add(repositoryId);
        }
        String from = """
                  FROM usage u JOIN symbol f ON f.id = u.from_symbol_id
                  JOIN maven_module m ON m.id = u.module_id JOIN scm_repository r ON r.id = m.repo_id
                """;
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(paging.offset());
        pageArgs.add(paging.size());
        List<UsageView> items = jdbc.query("""
                SELECT u.id, f.id AS from_id, f.symbol_key, f.kind AS from_kind, f.display_signature, u.kind,
                       u.confidence, r.id AS repo_id, r.project_key, r.slug, m.path, u.file_path, u.line_no, u.column_no,
                       u.snippet
                """ + from + where + """
                 ORDER BY r.project_key, r.slug, r.id, m.path, u.file_path, u.line_no, u.column_no, u.id
                 OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """, (rs, n) -> new UsageView(
                        rs.getLong("id"),
                        new SymbolRef(rs.getLong("from_id"), rs.getString("symbol_key"),
                                SymbolKind.valueOf(rs.getString("from_kind")), rs.getString("display_signature")),
                        UsageKind.valueOf(rs.getString("kind")),
                        Confidence.valueOf(rs.getString("confidence")),
                        repository(rs),
                        rs.getString("path"),
                        rs.getString("file_path"),
                        rs.getInt("line_no"),
                        rs.getInt("column_no"),
                        rs.getString("snippet")),
                pageArgs.toArray());
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        return new Page<>(items, paging.page(), paging.size(), total == null ? 0 : total);
    }

    public UsageSummary summary(long id) {
        Map<RepositoryRef, Map<String, List<UsageSummary.ClassUsage>>> tree = new LinkedHashMap<>();
        jdbc.query("""
                SELECT r.id AS repo_id, r.project_key, r.slug, m.path, f.class_fqn, COUNT(*) AS usages
                  FROM usage u JOIN symbol f ON f.id = u.from_symbol_id
                  JOIN maven_module m ON m.id = u.module_id JOIN scm_repository r ON r.id = m.repo_id
                 WHERE u.to_symbol_id = ?
                 GROUP BY r.id, r.project_key, r.slug, m.path, f.class_fqn
                 ORDER BY r.project_key, r.slug, r.id, m.path, f.class_fqn
                """, rs -> {
                    tree.computeIfAbsent(repository(rs), k -> new LinkedHashMap<>())
                            .computeIfAbsent(rs.getString("path"), k -> new ArrayList<>())
                            .add(new UsageSummary.ClassUsage(rs.getString("class_fqn"), rs.getLong("usages")));
                }, id);
        List<UsageSummary.RepositoryUsage> repositories = new ArrayList<>();
        long total = 0;
        for (Map.Entry<RepositoryRef, Map<String, List<UsageSummary.ClassUsage>>> repo : tree.entrySet()) {
            List<UsageSummary.ModuleUsage> modules = new ArrayList<>();
            long repoTotal = 0;
            for (Map.Entry<String, List<UsageSummary.ClassUsage>> module : repo.getValue().entrySet()) {
                long moduleTotal = module.getValue().stream().mapToLong(UsageSummary.ClassUsage::usages).sum();
                modules.add(new UsageSummary.ModuleUsage(module.getKey(), moduleTotal, module.getValue()));
                repoTotal += moduleTotal;
            }
            repositories.add(new UsageSummary.RepositoryUsage(repo.getKey(), repoTotal, modules));
            total += repoTotal;
        }
        return new UsageSummary(total, repositories.size(), repositories);
    }

    private static RepositoryRef repository(ResultSet rs) throws SQLException {
        return new RepositoryRef(rs.getLong("repo_id"), rs.getString("project_key"), rs.getString("slug"));
    }

    private List<SymbolRef> related(String condition, String otherColumn, long id) {
        return refs("SELECT DISTINCT " + REF_COLUMNS + " FROM usage u JOIN symbol s ON s.id = " + otherColumn
                + " WHERE " + condition + " ORDER BY s.symbol_key", id);
    }

    private List<SymbolRef> refs(String sql, long id) {
        return jdbc.query(sql, (rs, n) -> new SymbolRef(rs.getLong("id"), rs.getString("symbol_key"),
                SymbolKind.valueOf(rs.getString("kind")), rs.getString("display_signature")), id);
    }
}
