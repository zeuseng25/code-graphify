package com.graphify.repograph;

import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.UsageKind;
import com.graphify.store.Chunks;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/** Reads a repository's class and member graphs from the index with GROUP BY over USAGE (spec §9.1). */
@Repository
public class ClassGraphLoader {

    /** Group key prefixes of external nodes. */
    static final String REPOSITORY_GROUP = "repo:";
    static final String LIBRARY_GROUP = "lib:";

    /** The group of external types with no recorded artifact (e.g. the JDK). */
    static final ExternalGroup OTHER_LIBRARIES = new ExternalGroup(LIBRARY_GROUP + "other",
            "Other libraries (no artifact recorded)", NodeType.EXTERNAL_LIBRARY);

    private static final String CLASSES = """
            SELECT s.id, s.symbol_key, m.path
              FROM symbol s
              JOIN symbol_declaration d ON d.symbol_id = s.id
              JOIN maven_module m ON m.id = d.module_id
             WHERE m.repo_id = ? AND s.kind IN ('CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'ANNOTATION_TYPE')
             ORDER BY s.symbol_key, m.path
            """;

    private static final String EDGES = """
            SELECT f.class_fqn AS from_class, t.class_fqn AS to_class, u.kind, COUNT(*) AS weight,
                   MAX(t.artifact) AS artifact
              FROM usage u
              JOIN maven_module m ON m.id = u.module_id
              JOIN symbol f ON f.id = u.from_symbol_id
              JOIN symbol t ON t.id = u.to_symbol_id
             WHERE m.repo_id = ?
             GROUP BY f.class_fqn, t.class_fqn, u.kind
            """;

    /** Only usages whose target type is declared in this repository (when external types are not wanted). */
    private static final String INTERNAL_EDGES = """
            SELECT f.class_fqn AS from_class, t.class_fqn AS to_class, u.kind, COUNT(*) AS weight,
                   MAX(t.artifact) AS artifact
              FROM usage u
              JOIN maven_module m ON m.id = u.module_id
              JOIN symbol f ON f.id = u.from_symbol_id
              JOIN symbol t ON t.id = u.to_symbol_id
             WHERE m.repo_id = ?
               AND EXISTS (SELECT 1 FROM symbol c
                             JOIN symbol_declaration d ON d.symbol_id = c.id
                             JOIN maven_module dm ON dm.id = d.module_id
                            WHERE c.symbol_key = t.class_fqn AND dm.repo_id = ?)
             GROUP BY f.class_fqn, t.class_fqn, u.kind
            """;

    private static final String ARTIFACTS = """
            SELECT class_fqn, MAX(artifact) AS artifact
              FROM symbol
             WHERE artifact IS NOT NULL AND class_fqn IN (%s)
             GROUP BY class_fqn
            """;

    private static final String DECLARING_REPOSITORIES = """
            SELECT DISTINCT c.symbol_key, r.project_key, r.slug
              FROM symbol c
              JOIN symbol_declaration d ON d.symbol_id = c.id
              JOIN maven_module dm ON dm.id = d.module_id
              JOIN scm_repository r ON r.id = dm.repo_id
             WHERE dm.repo_id <> ? AND c.symbol_key IN (%s)
             ORDER BY c.symbol_key, r.project_key, r.slug
            """;

    private static final String FOCUS_MEMBERS = """
            SELECT id, symbol_key, class_fqn, kind, display_signature
              FROM symbol
             WHERE class_fqn = ? AND kind IN ('METHOD', 'CONSTRUCTOR', 'FIELD')
               AND EXISTS (SELECT 1 FROM symbol_declaration d JOIN maven_module m ON m.id = d.module_id
                            WHERE d.symbol_id = symbol.id AND m.repo_id = ?)
             ORDER BY symbol_key
            """;

    private static final String MEMBER_USES = """
            SELECT f_id, f_key, f_class, f_kind, f_signature, t_id, t_key, t_class, t_kind, t_signature, kind,
                   SUM(weight) AS weight
              FROM (
                    SELECT f.id AS f_id, f.symbol_key AS f_key, f.class_fqn AS f_class, f.kind AS f_kind,
                           f.display_signature AS f_signature,
                           t.id AS t_id, t.symbol_key AS t_key, t.class_fqn AS t_class, t.kind AS t_kind,
                           t.display_signature AS t_signature, u.kind AS kind, 1 AS weight
                      FROM usage u
                      JOIN maven_module m ON m.id = u.module_id
                      JOIN symbol f ON f.id = u.from_symbol_id
                      JOIN symbol t ON t.id = u.to_symbol_id
                     WHERE m.repo_id = ? AND f.class_fqn = ?
                    UNION ALL
                    SELECT f.id, f.symbol_key, f.class_fqn, f.kind, f.display_signature,
                           t.id, t.symbol_key, t.class_fqn, t.kind, t.display_signature, u.kind, 1
                      FROM usage u
                      JOIN maven_module m ON m.id = u.module_id
                      JOIN symbol f ON f.id = u.from_symbol_id
                      JOIN symbol t ON t.id = u.to_symbol_id
                     WHERE m.repo_id = ? AND t.class_fqn = ? AND f.class_fqn <> ?)
             GROUP BY f_id, f_key, f_class, f_kind, f_signature, t_id, t_key, t_class, t_kind, t_signature, kind
             ORDER BY f_key, t_key, kind
            """;

    private record RawExternal(String fromFqn, String toFqn, String artifact, UsageKind kind, long weight) {
    }

    private final JdbcTemplate jdbc;

    public ClassGraphLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Only the repository's declared types, with no edges: enough for the member view. */
    public ClassGraph loadClasses(long repositoryId) {
        return new ClassGraph(repositoryId, classes(repositoryId), List.of(), List.of());
    }

    private Map<String, ClassNode> classes(long repositoryId) {
        Map<String, ClassNode> classes = new TreeMap<>();
        jdbc.query(CLASSES, rs -> {
            // a type declared in two modules is placed in the first by path
            String fqn = rs.getString("symbol_key");
            classes.putIfAbsent(fqn, new ClassNode(rs.getLong("id"), fqn, ClassGraph.packageOf(fqn),
                    rs.getString("path")));
        }, repositoryId);
        return classes;
    }

    public ClassGraph load(long repositoryId, boolean includeExternal) {
        Map<String, ClassNode> classes = classes(repositoryId);
        List<ClassEdge> edges = new ArrayList<>();
        List<RawExternal> raw = new ArrayList<>();
        RowCallbackHandler edgeRow = rs -> {
            String from = rs.getString("from_class");
            String to = rs.getString("to_class");
            if (!classes.containsKey(from) || from.equals(to)) {
                return;
            }
            UsageKind kind = UsageKind.valueOf(rs.getString("kind"));
            long weight = rs.getLong("weight");
            if (classes.containsKey(to)) {
                edges.add(new ClassEdge(from, to, kind, weight));
            } else if (includeExternal) {
                raw.add(new RawExternal(from, to, rs.getString("artifact"), kind, weight));
            }
        };
        if (includeExternal) {
            jdbc.query(EDGES, edgeRow, repositoryId);
        } else {
            jdbc.query(INTERNAL_EDGES, edgeRow, repositoryId, repositoryId);
        }
        edges.sort(Comparator.comparing(ClassEdge::fromFqn).thenComparing(ClassEdge::toFqn)
                .thenComparing(ClassEdge::kind));
        return new ClassGraph(repositoryId, classes, edges, external(repositoryId, raw));
    }

    /** The groups the given external type FQNs are shown in: declaring repository, else artifact, else "other". */
    public Map<String, ExternalGroup> externalGroups(long repositoryId, Collection<String> fqns) {
        List<String> targets = fqns.stream().distinct().sorted().toList();
        Map<String, String> artifacts = new HashMap<>();
        for (List<String> chunk : Chunks.of(targets, Chunks.MAX_IN_LIST)) {
            jdbc.query(ARTIFACTS.formatted(Chunks.placeholders(chunk.size())),
                    rs -> {
                        artifacts.put(rs.getString("class_fqn"), rs.getString("artifact"));
                    }, chunk.toArray());
        }
        Map<String, String> declaring = declaring(repositoryId, targets);
        Map<String, ExternalGroup> groups = new HashMap<>();
        for (String fqn : targets) {
            groups.put(fqn, groupOf(declaring.get(fqn), artifacts.get(fqn)));
        }
        return groups;
    }

    public MemberGraph members(long repositoryId, String classFqn) {
        List<MemberRef> focus = jdbc.query(FOCUS_MEMBERS, (rs, row) -> new MemberRef(rs.getLong("id"),
                rs.getString("symbol_key"), rs.getString("class_fqn"), SymbolKind.valueOf(rs.getString("kind")),
                rs.getString("display_signature")), classFqn, repositoryId);
        List<MemberUse> uses = jdbc.query(MEMBER_USES, (rs, row) -> new MemberUse(ref(rs, "f_"), ref(rs, "t_"),
                UsageKind.valueOf(rs.getString("kind")), rs.getLong("weight")), repositoryId, classFqn, repositoryId, classFqn, classFqn);
        return new MemberGraph(focus, uses);
    }

    private Map<String, String> declaring(long repositoryId, List<String> targets) {
        Map<String, String> declaring = new HashMap<>();
        for (List<String> chunk : Chunks.of(targets, Chunks.MAX_IN_LIST - 1)) {
            List<Object> args = new ArrayList<>();
            args.add(repositoryId);
            args.addAll(chunk);
            jdbc.query(DECLARING_REPOSITORIES.formatted(Chunks.placeholders(chunk.size())), rs -> {
                // the first repository by project and slug wins
                declaring.putIfAbsent(rs.getString("symbol_key"),
                        rs.getString("project_key") + "/" + rs.getString("slug"));
            }, args.toArray());
        }
        return declaring;
    }

    private static ExternalGroup groupOf(String repository, String artifact) {
        return repository != null
                ? new ExternalGroup(REPOSITORY_GROUP + repository, repository, NodeType.EXTERNAL_REPOSITORY)
                : artifact != null
                        ? new ExternalGroup(LIBRARY_GROUP + artifact, artifact, NodeType.EXTERNAL_LIBRARY)
                        : OTHER_LIBRARIES;
    }

    private List<ExternalEdge> external(long repositoryId, List<RawExternal> raw) {
        if (raw.isEmpty()) {
            return List.of();
        }
        Map<String, String> declaring = declaring(repositoryId,
                raw.stream().map(RawExternal::toFqn).distinct().sorted().toList());
        List<ExternalEdge> external = new ArrayList<>();
        for (RawExternal edge : raw) {
            ExternalGroup group = groupOf(declaring.get(edge.toFqn()), edge.artifact());
            external.add(new ExternalEdge(edge.fromFqn(), group, edge.toFqn(), edge.kind(), edge.weight()));
        }
        external.sort(Comparator.comparing(ExternalEdge::fromFqn).thenComparing(ExternalEdge::toFqn)
                .thenComparing(ExternalEdge::kind));
        return external;
    }

    private static MemberRef ref(ResultSet rs, String prefix) throws SQLException {
        return new MemberRef(rs.getLong(prefix + "id"), rs.getString(prefix + "key"), rs.getString(prefix + "class"),
                SymbolKind.valueOf(rs.getString(prefix + "kind")), rs.getString(prefix + "signature"));
    }
}
