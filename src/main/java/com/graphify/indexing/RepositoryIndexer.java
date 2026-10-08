package com.graphify.indexing;

import com.graphify.indexer.IndexRequest;
import com.graphify.indexer.IndexerOptions;
import com.graphify.indexer.JavaRepositoryIndexer;
import com.graphify.indexer.ModuleSource;
import com.graphify.indexer.model.IndexResult;
import com.graphify.maven.ClasspathResolver;
import com.graphify.maven.ClasspathResult;
import com.graphify.maven.MavenModule;
import com.graphify.maven.MavenProject;
import com.graphify.maven.MavenProjectReader;
import com.graphify.repograph.RepoGraphService;
import com.graphify.scm.ScmConnection;
import com.graphify.scm.ScmConnections;
import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.store.ClasspathMode;
import com.graphify.store.ModuleRecord;
import com.graphify.store.RepositoryIndex;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.store.RepositoryNotFoundException;
import com.graphify.store.WriteSummary;
import com.graphify.workspace.GitWorkspace;
import com.graphify.workspace.RemoteHead;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Indexes one repository: head check, checkout, Maven model, classpath, JDT index, write (spec §3.2 steps 2–8). A run
 * calls the phases separately (workspace artifacts spec §3): {@link #prepare}, then, after the providers were
 * installed, {@link #complete}.
 */
@Service
public class RepositoryIndexer {

    private static final Logger log = LoggerFactory.getLogger(RepositoryIndexer.class);

    private record RepositoryRow(long id, long connectionId, String projectKey, String slug, String cloneUrl,
            String lastIndexedCommit, String lastStatus, String connectionBaseUrl, String lastInstalledCommit) {
    }

    private final JdbcTemplate jdbc;
    private final ScmConnections connections;
    private final GitWorkspace workspace;
    private final MavenProjectReader mavenReader;
    private final ClasspathResolver classpathResolver;
    private final RepositoryIndexWriter writer;
    private final IndexRunRecorder runs;
    private final AppSettings settings;
    private final RepoGraphService graphs;
    private final JavaRepositoryIndexer javaIndexer = new JavaRepositoryIndexer();

    public RepositoryIndexer(JdbcTemplate jdbc, ScmConnections connections, GitWorkspace workspace,
            MavenProjectReader mavenReader, ClasspathResolver classpathResolver, RepositoryIndexWriter writer,
            IndexRunRecorder runs, AppSettings settings, RepoGraphService graphs) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.workspace = workspace;
        this.mavenReader = mavenReader;
        this.classpathResolver = classpathResolver;
        this.writer = writer;
        this.runs = runs;
        this.settings = settings;
        this.graphs = graphs;
    }

    /** One repository on its own (no install phase): prepare, then complete. */
    public RepoIndexOutcome index(long runId, long repositoryId, boolean force) {
        Preparation preparation = prepare(repositoryId, force);
        // the prepared repository carries its own preparation time; complete adds it
        return preparation.finished() != null ? finish(runId, repositoryId, preparation.finished())
                : complete(runId, preparation.prepared(), false, null, System.nanoTime());
    }

    /** Phase 1: the head check, checkout and pom reading; the skip rule is only noted (spec §3.1). */
    public Preparation prepare(long repositoryId, boolean force) {
        long startedAt = System.nanoTime();
        RepositoryRow repo = load(repositoryId);
        ScmConnection connection = connectionOf(repo);
        RemoteHead head;
        try {
            head = workspace.remoteHead(repo.cloneUrl(), connection.gitAuthorization());
        } catch (RuntimeException e) {
            rethrowIfInterrupted(e);
            return new Preparation(null, outcome(RepoIndexStatus.CLONE_FAILED, null, null, message(e), startedAt));
        }
        jdbc.update("UPDATE scm_repository SET default_branch = ? WHERE id = ?", head.branch(), repo.id());
        Path checkout;
        String commit;
        try {
            checkout = workspace.directoryFor(repo.connectionId(), repo.projectKey(), repo.slug());
            // the branch may have moved since ls-remote; what was checked out is what gets indexed
            commit = workspace.checkout(checkout, repo.cloneUrl(), head.branch(), connection.gitAuthorization());
        } catch (RuntimeException e) {
            rethrowIfInterrupted(e);
            return new Preparation(null, outcome(RepoIndexStatus.CLONE_FAILED, null, null, message(e), startedAt));
        }
        MavenProject project;
        try {
            project = mavenReader.read(checkout, settings.getList(SettingKeys.INDEX_SOURCE_ROOTS),
                    settings.getInt(SettingKeys.INDEX_POM_SEARCH_DEPTH));
        } catch (RuntimeException e) {
            rethrowIfInterrupted(e);
            return new Preparation(null, outcome(RepoIndexStatus.FAILED, commit, null, message(e), startedAt));
        }
        // a partial index is retried on the same commit: the classpath may resolve now
        boolean unchanged = !force && commit.equals(repo.lastIndexedCommit())
                && !RepoIndexStatus.SUCCESS_PARTIAL.name().equals(repo.lastStatus());
        return new Preparation(new PreparedRepository(repo.id(), repo.slug(), commit, checkout, project, unchanged,
                repo.lastInstalledCommit(), elapsed(startedAt)), null);
    }

    /**
     * Phase 3: index a prepared repository, or skip it as unchanged unless {@code reindex} (a provider of it was
     * installed in this run), then record its outcome together with its own install ({@code install}, null when it
     * provided nothing). {@code startedAt} is when this phase began; the preparation time is added to it.
     */
    public RepoIndexOutcome complete(long runId, PreparedRepository prepared, boolean reindex,
            ArtifactInstallOutcome install, long startedAt) {
        RepoIndexOutcome outcome;
        if (prepared.unchanged() && !reindex) {
            // a failed or interrupted analysis is redone at the same commit
            if (!graphs.isCurrent(prepared.repositoryId(), prepared.commit())) {
                analyzeGraph(prepared.repositoryId(), prepared.commit());
            }
            outcome = outcome(RepoIndexStatus.SKIPPED_UNCHANGED, prepared.commit(), null, null, startedAt);
        } else {
            outcome = indexPrepared(prepared, startedAt);
        }
        outcome = new RepoIndexOutcome(outcome.status(), outcome.commit(), outcome.classpathMode(), outcome.error(),
                outcome.symbols(), outcome.usages(), outcome.warnings(),
                outcome.durationMillis() + prepared.prepareMillis());
        if (outcome.status() != RepoIndexStatus.SKIPPED_UNCHANGED) {
            jdbc.update("UPDATE scm_repository SET last_status = ? WHERE id = ?", outcome.status().name(),
                    prepared.repositoryId());
        }
        runs.record(runId, prepared.repositoryId(), outcome, install);
        return outcome;
    }

    /** Records a repository whose preparation already ended it (CLONE_FAILED, or a pom read that threw). */
    public RepoIndexOutcome finish(long runId, long repositoryId, RepoIndexOutcome finished) {
        jdbc.update("UPDATE scm_repository SET last_status = ? WHERE id = ?", finished.status().name(), repositoryId);
        runs.record(runId, repositoryId, finished);
        return finished;
    }

    /** The commit whose artifacts were last installed successfully (the install phase's skip rule, spec §3.2). */
    public void recordInstalled(long repositoryId, String commit) {
        jdbc.update("UPDATE scm_repository SET last_installed_commit = ? WHERE id = ?", commit, repositoryId);
    }

    private ScmConnection connectionOf(RepositoryRow repo) {
        ScmConnection connection = connections.find(repo.connectionId())
                .orElseThrow(() -> new IllegalStateException("Repository " + repo.id() + " has no connection"));
        if (!connection.baseUrl().equals(repo.connectionBaseUrl())) {
            // repointed between the two reads: the clone URL may still name the old host, never pair it with this token
            throw new RepositoryNotFoundException(repo.id());
        }
        return connection;
    }

    private RepoIndexOutcome indexPrepared(PreparedRepository prepared, long startedAt) {
        MavenProject project = prepared.project();
        Path checkout = prepared.checkout();
        String commit = prepared.commit();
        long repositoryId = prepared.repositoryId();
        try {
            List<MavenModule> withSources = project.modules().stream()
                    .filter(m -> m.sourceRoots().stream().anyMatch(RepositoryIndexer::containsJava)).toList();
            if (withSources.isEmpty()) {
                // clear whatever an earlier Java commit left and record this commit, so it is not rescanned; the pom
                // coordinates stay, so a pom-only repository (a parent, a BOM) can still provide them (spec §3.2)
                List<ModuleRecord> records = project.modules().stream().filter(MavenModule::hasPom)
                        .map(m -> new ModuleRecord(m.path(), m.groupId(), m.artifactId(), m.version(),
                                ClasspathMode.NONE, m.dependencies(), m.packaging()))
                        .toList();
                write(new RepositoryIndex(repositoryId, commit, records,
                        new IndexResult(List.of(), List.of(), List.of(), List.of())));
                return outcome(RepoIndexStatus.SKIPPED_NOT_JAVA, commit, null, null, startedAt);
            }
            ClasspathResult classpaths = classpathResolver.resolve(checkout, project.modules());
            List<ModuleSource> sources = new ArrayList<>();
            List<ModuleRecord> records = new ArrayList<>();
            for (MavenModule module : project.modules()) {
                List<Path> classpath = classpaths.classpaths().get(module.path());
                sources.add(new ModuleSource(module.path(), module.sourceRoots(), classpath == null ? List.of() : classpath));
                records.add(new ModuleRecord(module.path(), module.groupId(), module.artifactId(), module.version(),
                        classpath == null ? ClasspathMode.NONE : ClasspathMode.FULL, module.dependencies(),
                        module.packaging()));
            }
            IndexResult result = javaIndexer.index(new IndexRequest(checkout, sources, new IndexerOptions(
                    settings.getInt(SettingKeys.INDEX_PARSE_BATCH_SIZE),
                    settings.getInt(SettingKeys.USAGE_SNIPPET_MAX_LENGTH))));
            WriteSummary summary = write(new RepositoryIndex(repositoryId, commit, records, result));
            long full = withSources.stream().filter(m -> classpaths.classpaths().containsKey(m.path())).count();
            String mode = full == withSources.size() ? "FULL" : full == 0 ? "NONE" : "PARTIAL";
            RepoIndexStatus status = full == withSources.size() ? RepoIndexStatus.SUCCESS : RepoIndexStatus.SUCCESS_PARTIAL;
            List<String> notes = new ArrayList<>(project.warnings());
            if (classpaths.error() != null) {
                notes.add(classpaths.error());
            }
            return new RepoIndexOutcome(status, commit, mode, notes.isEmpty() ? null : UrlMasking.mask(String.join("\n", notes)),
                    summary.symbols(), summary.usages(), result.warnings().size() + project.warnings().size(),
                    elapsed(startedAt));
        } catch (RuntimeException e) {
            rethrowIfInterrupted(e);
            return outcome(RepoIndexStatus.FAILED, commit, null, message(e), startedAt);
        }
    }

    /** One retry when another repository's write held the same symbol rows (plan 2 follow-up). */
    private WriteSummary write(RepositoryIndex index) {
        WriteSummary summary;
        try {
            summary = writer.replace(index);
        } catch (PessimisticLockingFailureException e) {
            summary = writer.replace(index);
        }
        analyzeGraph(index.repositoryId(), index.commit());
        return summary;
    }

    /** Graph analyses are derived data: a failure keeps the index and the previous analysis (reported as stale). */
    private void analyzeGraph(long repositoryId, String commit) {
        try {
            graphs.analyze(repositoryId, commit);
        } catch (RuntimeException e) {
            rethrowIfInterrupted(e);
            log.warn("Repository {}: the graph analysis failed: {}", repositoryId,
                    UrlMasking.mask(String.valueOf(e.getMessage())));
        }
    }

    private RepositoryRow load(long repositoryId) {
        return jdbc.query("""
                SELECT r.id, r.connection_id, r.project_key, r.slug, r.clone_url, r.last_indexed_commit,
                       r.last_status, c.base_url, r.last_installed_commit
                  FROM scm_repository r JOIN scm_connection c ON c.id = r.connection_id
                 WHERE r.id = ? AND r.active = 1
                """, (rs, row) -> new RepositoryRow(rs.getLong("id"), rs.getLong("connection_id"),
                        rs.getString("project_key"), rs.getString("slug"), rs.getString("clone_url"),
                        rs.getString("last_indexed_commit"), rs.getString("last_status"), rs.getString("base_url"),
                        rs.getString("last_installed_commit")),
                repositoryId)
                .stream().findFirst().orElseThrow(() -> new RepositoryNotFoundException(repositoryId));
    }

    private static boolean containsJava(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.anyMatch(f -> f.toString().endsWith(".java") && Files.isRegularFile(f));
        } catch (IOException e) {
            return false;
        }
    }

    private static RepoIndexOutcome outcome(RepoIndexStatus status, String commit, String mode, String error,
            long startedAt) {
        return new RepoIndexOutcome(status, commit, mode, error, 0, 0, 0, elapsed(startedAt));
    }

    private static String message(RuntimeException e) {
        String text = e.getClass().getSimpleName() + ": " + e.getMessage();
        return UrlMasking.mask(text);
    }

    private static long elapsed(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /**
     * An interrupt surfacing through git, Maven or the database is not this repository's failure: it is rethrown, so
     * the executor leaves the repository to recovery (INTERRUPTED, previous index kept) instead of recording it FAILED.
     */
    static void rethrowIfInterrupted(RuntimeException e) {
        if (IndexRunExecutor.isInterruption(e, Thread.currentThread().isInterrupted())) {
            throw e;
        }
    }
}
