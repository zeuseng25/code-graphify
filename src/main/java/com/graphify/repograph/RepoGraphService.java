package com.graphify.repograph;

import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Repository graph analyses (spec §9.2) and the graph view, report and export. */
@Service
public class RepoGraphService {

    /** Classes with an enabled entry-point annotation on themselves or a member (spec §9.2 "Giriş noktaları"). */
    private static final String ENTRY_CLASSES = """
            SELECT DISTINCT f.class_fqn
              FROM usage u
              JOIN maven_module m ON m.id = u.module_id
              JOIN symbol f ON f.id = u.from_symbol_id
              JOIN symbol t ON t.id = u.to_symbol_id
             WHERE m.repo_id = ? AND u.kind = 'ANNOTATION'
               AND t.symbol_key IN (SELECT annotation_fqn FROM entry_point_annotation WHERE enabled = 1)
            """;

    private final JdbcTemplate jdbc;
    private final ClassGraphLoader loader;
    private final RepoGraphStore store;
    private final AppSettings settings;

    public RepoGraphService(JdbcTemplate jdbc, ClassGraphLoader loader, RepoGraphStore store, AppSettings settings) {
        this.jdbc = jdbc;
        this.loader = loader;
        this.store = store;
        this.settings = settings;
    }

    /** Whether the stored analyses describe this commit. */
    public boolean isCurrent(long repositoryId, String commit) {
        return store.state(repositoryId).filter(state -> state.commit().equals(commit)).isPresent();
    }

    /** Recomputes and stores a repository's analyses for the commit just written (spec §3.2 step 9). */
    public void analyze(long repositoryId, String commit) {
        ClassGraph graph = loader.load(repositoryId, false);
        Set<String> entryClasses = new HashSet<>(jdbc.queryForList(ENTRY_CLASSES, String.class, repositoryId));
        RepoGraphAnalyzer.Analysis analysis = RepoGraphAnalyzer.analyze(graph, entryClasses,
                settings.getInt(SettingKeys.GRAPH_COMMUNITY_SEED),
                settings.getInt(SettingKeys.GRAPH_COMMUNITY_MAX_ITERATIONS));
        store.replace(repositoryId, commit, graph, analysis, settings.getInt(SettingKeys.STORE_JDBC_BATCH_SIZE));
    }

    public RepoGraph graph(long repositoryId, GraphLevel level, String focus, boolean includeExternal) {
        return graph(repositoryId, level, focus, includeExternal, settings.getInt(SettingKeys.GRAPH_MAX_NODES));
    }

    RepoGraph graph(long repositoryId, GraphLevel level, String focus, boolean includeExternal, int maxNodes) {
        requireRepository(repositoryId);
        String normalized = normalizeFocus(focus, level);
        Map<Long, ClassMetrics> metrics = store.metrics(repositoryId);
        if (level == GraphLevel.METHOD) {
            if (normalized == null) {
                throw new InvalidRequestException("focus (a class name) is required at METHOD level");
            }
            ClassGraph declared = loader.loadClasses(repositoryId);
            if (!declared.classes().containsKey(normalized)) {
                throw new NotFoundException("No class " + normalized + " is declared in repository " + repositoryId);
            }
            MemberGraph members = withExternalGroups(repositoryId, loader.members(repositoryId, normalized), declared,
                    includeExternal);
            // the full edge aggregation only runs when the member view is rolled up
            return new GraphBuilder(declared, () -> loader.load(repositoryId, includeExternal), metrics)
                    .build(level, normalized, members, maxNodes);
        }
        ClassGraph graph = loader.load(repositoryId, includeExternal);
        if (normalized != null && level != GraphLevel.MODULE && graph.classes().values().stream()
                .noneMatch(c -> c.packageName().equals(normalized) || c.packageName().startsWith(normalized + "."))) {
            throw new NotFoundException("No package " + normalized + " in repository " + repositoryId);
        }
        return new GraphBuilder(graph, metrics).build(level, normalized, null, maxNodes);
    }

    public RepoGraph exportGraph(long repositoryId, GraphLevel level, String focus, boolean includeExternal) {
        return graph(repositoryId, level, focus, includeExternal, settings.getInt(SettingKeys.GRAPH_EXPORT_MAX_NODES));
    }

    public RepoGraphReport report(long repositoryId) {
        String indexedCommit = requireRepository(repositoryId);
        ClassGraph graph = loader.load(repositoryId, false);
        Optional<AnalysisState> state = store.state(repositoryId);
        int top = settings.getInt(SettingKeys.GRAPH_REPORT_TOP_N);
        Set<String> modules = graph.classes().values().stream().map(ClassNode::modulePath)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> packages = graph.classes().values().stream().map(ClassNode::packageName)
                .collect(Collectors.toCollection(TreeSet::new));
        long dependencies = graph.edges().stream().map(e -> e.fromFqn() + "\n" + e.toFqn()).distinct().count();
        String analyzedCommit = state.map(AnalysisState::commit).orElse(null);
        boolean stale = indexedCommit != null && !indexedCommit.equals(analyzedCommit);
        return new RepoGraphReport(repositoryId, indexedCommit, analyzedCommit,
                state.map(AnalysisState::analyzedAt).orElse(null), stale, modules.size(), packages.size(),
                graph.classes().size(), dependencies, state.map(AnalysisState::communityCount).orElse(0),
                store.critical(repositoryId, top), store.communities(repositoryId, top), store.cycles(repositoryId),
                store.entryPointCount(repositoryId), store.entryPointClasses(repositoryId, top));
    }

    private MemberGraph withExternalGroups(long repositoryId, MemberGraph members, ClassGraph declared,
            boolean includeExternal) {
        if (!includeExternal) {
            return members;
        }
        Set<String> external = new HashSet<>();
        for (MemberUse use : members.uses()) {
            for (MemberRef ref : List.of(use.from(), use.to())) {
                if (!declared.classes().containsKey(ref.classFqn())) {
                    external.add(ref.classFqn());
                }
            }
        }
        return new MemberGraph(members.focusMembers(), members.uses(), loader.externalGroups(repositoryId, external));
    }

    /** Blank is no focus; trailing dots are dropped; the default package's label means the default package (""). */
    private static String normalizeFocus(String focus, GraphLevel level) {
        if (focus == null || focus.isBlank()) {
            return null;
        }
        String stripped = focus.strip();
        if (level == GraphLevel.METHOD) {
            return stripped;
        }
        if (stripped.equals(RepoGraphAnalyzer.DEFAULT_PACKAGE)) {
            return "";
        }
        int end = stripped.length();
        while (end > 0 && stripped.charAt(end - 1) == '.') {
            end--;
        }
        return end == 0 ? null : stripped.substring(0, end);
    }

    /** The repository's last indexed commit (null when never indexed); 404 for an unknown repository. */
    String requireRepository(long repositoryId) {
        List<String> commits = jdbc.query("SELECT last_indexed_commit FROM scm_repository WHERE id = ?",
                (rs, row) -> rs.getString(1), repositoryId);
        if (commits.isEmpty()) {
            throw new NotFoundException("No repository with id " + repositoryId);
        }
        return commits.getFirst();
    }
}
