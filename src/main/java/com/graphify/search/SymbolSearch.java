package com.graphify.search;

import com.graphify.api.Page;
import com.graphify.api.Paging;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Case-insensitive symbol search over the {@code search_class}/{@code search_member} virtual columns. */
@Repository
public class SymbolSearch {

    private static final String TYPE_KINDS = "('CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'ANNOTATION_TYPE')";

    private final JdbcTemplate jdbc;

    public SymbolSearch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page<SymbolHit> search(String text, SymbolKind kind, Long repositoryId, Paging paging) {
        SymbolQuery query = SymbolQuery.parse(text);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (query.word() != null) {
            if (kind == null) {
                where.append(" AND ((s.search_class = ? AND s.kind IN ").append(TYPE_KINDS)
                        .append(") OR s.search_member = ?)");
            } else {
                where.append(" AND (s.search_class = ? OR s.search_member = ?)");
            }
            args.add(query.word());
            args.add(query.word());
        }
        if (query.simpleClass() != null) {
            where.append(" AND s.search_class = ?");
            args.add(query.simpleClass());
        }
        if (query.qualifiedClass() != null) {
            where.append(" AND UPPER(REPLACE(s.class_fqn, '$', '.')) = UPPER(?)");
            args.add(query.qualifiedClass());
        }
        if (query.member() != null) {
            where.append(" AND s.search_member = ?");
            args.add(query.member());
        } else if (kind == null && query.word() == null) {
            where.append(" AND s.kind IN ").append(TYPE_KINDS);
        }
        if (kind != null) {
            where.append(" AND s.kind = ?");
            args.add(kind.name());
        }
        if (repositoryId != null) {
            where.append("""
                     AND (EXISTS (SELECT 1 FROM usage u JOIN maven_module m ON m.id = u.module_id
                                  JOIN scm_repository r ON r.id = m.repo_id
                                  WHERE u.to_symbol_id = s.id AND r.id = ?)
                       OR EXISTS (SELECT 1 FROM symbol_declaration d JOIN maven_module m ON m.id = d.module_id
                                  JOIN scm_repository r ON r.id = m.repo_id
                                  WHERE d.symbol_id = s.id AND r.id = ?))
                    """);
            args.add(repositoryId);
            args.add(repositoryId);
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(paging.offset());
        pageArgs.add(paging.size());
        List<SymbolHit> items = jdbc.query("""
                SELECT s.id, s.symbol_key, s.kind, s.display_signature, s.origin, s.name_only,
                       (SELECT COUNT(*) FROM usage u WHERE u.to_symbol_id = s.id) AS usage_count,
                       (SELECT COUNT(DISTINCT m.repo_id) FROM usage u JOIN maven_module m ON m.id = u.module_id
                         WHERE u.to_symbol_id = s.id) AS repo_count
                  FROM symbol s
                """ + where + """
                 ORDER BY CASE s.origin WHEN 'SOURCE' THEN 0 ELSE 1 END, s.name_only, usage_count DESC, s.symbol_key
                 OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """, (rs, row) -> new SymbolHit(
                        rs.getLong("id"),
                        rs.getString("symbol_key"),
                        SymbolKind.valueOf(rs.getString("kind")),
                        rs.getString("display_signature"),
                        SymbolOrigin.valueOf(rs.getString("origin")),
                        rs.getInt("name_only") == 1,
                        rs.getLong("usage_count"),
                        rs.getInt("repo_count")),
                pageArgs.toArray());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM symbol s" + where, Long.class, args.toArray());
        return new Page<>(items, paging.page(), paging.size(), total == null ? 0 : total);
    }
}
