package com.graphify.indexing;

import com.graphify.maven.ArtifactInstaller;
import com.graphify.maven.Gav;
import com.graphify.maven.MavenModule;
import com.graphify.scm.ConnectionSyncStatus;
import com.graphify.scm.RepositorySync;
import com.graphify.scm.ScmAuthenticationException;
import com.graphify.scm.ScmConnection;
import com.graphify.scm.ScmConnections;
import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.store.Chunks;
import com.graphify.store.ConnectionPoolSizer;
import com.graphify.store.SymbolCleanup;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Carries out one index run (spec §3.2, §8): syncs the connections in scope, then runs three phases over their active
 * repositories on index.parallelism workers (workspace artifacts spec §3): prepare (checkout and read the projects),
 * install the providers layer by layer into the local Maven repository, then index. A failing connection, repository
 * or install never stops the others. The caller holds the index lock and has inserted the RUNNING index_run row.
 */
@Service
public class IndexRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(IndexRunExecutor.class);

    /** What a run indexes, and the connection notes for index_run.error. */
    private record Plan(List<Long> repositoryIds, boolean everyConnectionFailed, List<String> notes) {
    }

    private final JdbcTemplate jdbc;
    private final ScmConnections connections;
    private final RepositorySync sync;
    private final RepositoryIndexer indexer;
    private final IndexRunRecorder runs;
    private final ConnectionPoolSizer pool;
    private final AppSettings settings;
    private final ModuleCoordinates coordinates;
    private final ArtifactInstaller installer;
    private final SymbolCleanup cleanup;

    /**
     * The install phase's result: each provider's install, the commit of each provider INSTALLED in this run, and
     * each consumer's providers with their closure.
     */
    private record Provided(Map<Long, ArtifactInstallOutcome> installs, Map<Long, String> installedCommits,
            Map<Long, Set<Long>> transitiveProvidersOf) {
    }

    public IndexRunExecutor(JdbcTemplate jdbc, ScmConnections connections, RepositorySync sync,
            RepositoryIndexer indexer, IndexRunRecorder runs, ConnectionPoolSizer pool, AppSettings settings,
            ModuleCoordinates coordinates, ArtifactInstaller installer, SymbolCleanup cleanup) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.sync = sync;
        this.indexer = indexer;
        this.runs = runs;
        this.pool = pool;
        this.settings = settings;
        this.coordinates = coordinates;
        this.installer = installer;
        this.cleanup = cleanup;
    }

    /**
     * Symbols this run stopped referencing (a name-only guess now resolved, a removed method) are deleted while the
     * run still holds the index lock and every write is done (spec §4.4), so search and impact never show them. A
     * failure only logs: the weekly cleanup.orphan_symbols_cron job removes them later.
     */
    private void deleteOrphans(long runId) {
        try {
            int deleted = cleanup.deleteOrphans();
            if (deleted > 0) {
                log.info("Index run {} deleted {} orphan symbols", runId, deleted);
            }
        } catch (RuntimeException e) {
            log.warn("Index run {} could not delete orphan symbols: {}", runId, masked(e));
        }
    }

    public RunStatus execute(long runId, RunScope scope, Long scopeId, boolean force) {
        RunStatus status;
        String error = null;
        boolean interrupted = false;
        try {
            Plan plan = plan(runId, scope, scopeId);
            error = plan.notes().isEmpty() ? null : String.join("\n", plan.notes());
            indexAll(runId, plan.repositoryIds(), force);
            status = runs.isCancelRequested(runId) ? RunStatus.CANCELLED
                    : plan.everyConnectionFailed() ? RunStatus.FAILED : RunStatus.SUCCESS;
        } catch (InterruptedException e) {
            interrupted = true;
            status = RunStatus.INTERRUPTED;
            error = IndexRunRecorder.INTERRUPTED_RUN;
        } catch (RuntimeException e) {
            if (isInterruption(e, Thread.currentThread().isInterrupted())) {
                interrupted = true;
                status = RunStatus.INTERRUPTED;
                error = IndexRunRecorder.INTERRUPTED_RUN;
            } else {
                status = RunStatus.FAILED;
                error = masked(e);
                log.error("Index run {} failed: {}", runId, error);
            }
        }
        // ojdbc fails on an interrupted thread: clear the flag so finish is written, and restore it afterwards
        if (Thread.interrupted() && !interrupted) {
            interrupted = true;
            status = RunStatus.INTERRUPTED;
            error = IndexRunRecorder.INTERRUPTED_RUN;
        }
        if (!interrupted) {
            deleteOrphans(runId);
            // ojdbc fails on an interrupted thread: an interrupt during cleanup is cleared so finish is written
            if (Thread.interrupted()) {
                interrupted = true;
            }
        }
        try {
            runs.finish(runId, status, error);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Index run {} finished: {}", runId, status);
        return status;
    }

    private Plan plan(long runId, RunScope scope, Long scopeId) throws InterruptedException {
        if (scope == RunScope.REPOSITORY) {
            return new Plan(List.of(scopeId), false, List.of());
        }
        if (Thread.interrupted()) { // shutdown before the run began: touch nothing
            throw new InterruptedException("Interrupted before syncing");
        }
        // a connection disabled after the run was accepted is not synced: the run indexes nothing
        List<ScmConnection> inScope = scope == RunScope.ALL ? connections.enabled()
                : connections.enabled().stream().filter(c -> c.id() == scopeId).toList();
        List<Long> synced = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (scope == RunScope.CONNECTION && inScope.isEmpty()) {
            notes.add("connection " + scopeId + " is disabled or deleted; nothing was indexed");
        }
        for (ScmConnection connection : inScope) {
            if (Thread.interrupted()) { // shutdown: sync no further connections
                throw new InterruptedException("Interrupted before syncing connection " + connection.id());
            }
            if (runs.isCancelRequested(runId)) {
                break;
            }
            try {
                RepositorySync.SyncResult result = sync.sync(connection);
                if (result.deactivationsSkipped() > 0 || result.purgesSkipped() > 0) {
                    List<String> parts = new ArrayList<>();
                    if (result.deactivationsSkipped() > 0) {
                        parts.add(result.deactivationsSkipped() + " repositories are no longer listed; deactivation "
                                + "skipped");
                    }
                    if (result.purgesSkipped() > 0) {
                        parts.add(result.purgesSkipped() + " indexed repositories are no longer listed; index purge "
                                + "skipped");
                    }
                    String note = String.join("; ", parts) + " (scm.max_deactivation_percent)";
                    connections.recordSync(connection.id(), ConnectionSyncStatus.DEACTIVATION_SKIPPED, note);
                    notes.add("connection '" + connection.name() + "': " + note);
                } else {
                    connections.recordSync(connection.id(), ConnectionSyncStatus.SUCCESS, null);
                }
                synced.add(connection.id());
            } catch (ScmAuthenticationException e) {
                connections.recordSync(connection.id(), ConnectionSyncStatus.AUTH_FAILED, e.getMessage());
                notes.add("connection '" + connection.name() + "': AUTH_FAILED: " + masked(e));
            } catch (RuntimeException e) {
                if (isInterruption(e, Thread.interrupted())) {
                    throw new InterruptedException("Interrupted while syncing connection " + connection.id());
                }
                connections.recordSync(connection.id(), ConnectionSyncStatus.FAILED, masked(e));
                notes.add("connection '" + connection.name() + "': FAILED: " + masked(e));
            }
        }
        List<Long> repositoryIds = synced.isEmpty() ? List.of() : jdbc.queryForList("SELECT id FROM scm_repository "
                + "WHERE active = 1 AND connection_id IN (" + Chunks.placeholders(synced.size()) + ") "
                + "ORDER BY project_key, slug, id", Long.class, synced.toArray());
        return new Plan(repositoryIds, !inScope.isEmpty() && synced.isEmpty(), notes);
    }

    private void indexAll(long runId, List<Long> repositoryIds, boolean force) throws InterruptedException {
        if (repositoryIds.isEmpty()) {
            return;
        }
        int workers = Math.min(settings.getInt(SettingKeys.INDEX_PARALLELISM), repositoryIds.size());
        pool.ensureCapacity(workers);
        ExecutorService executor = Executors.newFixedThreadPool(workers,
                Thread.ofPlatform().name("index-run-" + runId + "-", 1).factory());
        try {
            Map<Long, PreparedRepository> prepared = prepareAll(executor, runId, repositoryIds, force);
            if (runs.isCancelRequested(runId)) {
                prepared.keySet().forEach(this::clearMarker);
                return;
            }
            Provided provided = provide(executor, runId, repositoryIds, prepared, force);
            if (runs.isCancelRequested(runId)) {
                prepared.keySet().forEach(this::clearMarker);
                return;
            }
            Map<Long, Future<RepoIndexOutcome>> tasks = new LinkedHashMap<>();
            for (PreparedRepository repository : prepared.values()) {
                boolean reindex = reindex(repository.repositoryId(), provided.transitiveProvidersOf(),
                        provided.installs());
                ArtifactInstallOutcome install = provided.installs().get(repository.repositoryId());
                tasks.put(repository.repositoryId(),
                        executor.submit(() -> completeSafely(runId, repository, reindex, install)));
            }
            Map<Long, RepoIndexOutcome> outcomes = await(runId, tasks);
            if (!runs.isCancelRequested(runId)) {
                installedToRecord(provided.installedCommits(), provided.transitiveProvidersOf(), prepared.keySet(),
                        outcomes).forEach(indexer::recordInstalled);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Spec §3.3 exception (§9: the closure): an unchanged consumer is indexed again when any repository it needs,
     * directly or through other providers, was installed in this run, since its classpath may have changed.
     */
    static boolean reindex(long repositoryId, Map<Long, Set<Long>> transitiveProvidersOf,
            Map<Long, ArtifactInstallOutcome> installs) {
        return transitiveProvidersOf.getOrDefault(repositoryId, Set.of()).stream().map(installs::get)
                .anyMatch(i -> i != null && i.status() == ArtifactInstallStatus.INSTALLED);
    }

    /**
     * The providers whose install may be remembered (scm_repository.last_installed_commit): every repository of the
     * run that needs the provider, directly or through others, was indexed in this run without failing. Otherwise
     * the commit is not remembered, so the next run installs the provider again and so re-indexes its consumers.
     */
    static Map<Long, String> installedToRecord(Map<Long, String> installedCommits,
            Map<Long, Set<Long>> transitiveProvidersOf, Set<Long> inRun, Map<Long, RepoIndexOutcome> outcomes) {
        Map<Long, String> record = new LinkedHashMap<>();
        installedCommits.forEach((provider, commit) -> {
            boolean consumersIndexed = inRun.stream()
                    .filter(consumer -> transitiveProvidersOf.getOrDefault(consumer, Set.of()).contains(provider))
                    .map(outcomes::get)
                    .allMatch(outcome -> outcome != null && outcome.status() != RepoIndexStatus.FAILED);
            if (consumersIndexed) {
                record.put(provider, commit);
            }
        });
        return record;
    }

    private Map<Long, PreparedRepository> prepareAll(ExecutorService executor, long runId, List<Long> repositoryIds,
            boolean force) throws InterruptedException {
        Map<Long, Future<PreparedRepository>> tasks = new LinkedHashMap<>();
        for (Long repositoryId : repositoryIds) {
            tasks.put(repositoryId, executor.submit(() -> prepareSafely(runId, repositoryId, force)));
        }
        Map<Long, PreparedRepository> prepared = new LinkedHashMap<>();
        for (Map.Entry<Long, Future<PreparedRepository>> task : tasks.entrySet()) {
            try {
                PreparedRepository repository = task.getValue().get();
                if (repository != null) {
                    prepared.put(task.getKey(), repository);
                }
            } catch (ExecutionException e) {
                // only reachable when even recording the failure failed (database down)
                log.error("Index run {}: repository {} could not be prepared: {}", runId, task.getKey(),
                        masked(e.getCause()));
            }
        }
        return prepared;
    }

    /**
     * Phase 1 for one repository; null when it already has its outcome, was cancelled or was interrupted. A prepared
     * repository keeps its in-progress marker until phase 3 (an interrupt in between leaves it to recovery).
     */
    PreparedRepository prepareSafely(long runId, long repositoryId, boolean force) {
        if (runs.isCancelRequested(runId)) {
            return null;
        }
        long startedAt = System.nanoTime();
        try {
            runs.markStarted(runId, repositoryId);
            Preparation preparation = indexer.prepare(repositoryId, force);
            if (preparation.finished() == null) {
                return preparation.prepared();
            }
            indexer.finish(runId, repositoryId, preparation.finished());
        } catch (Throwable e) { // a parser StackOverflowError must not leave the repository unrecorded
            if (isInterruption(e, Thread.currentThread().isInterrupted())) {
                log.warn("Index run {}: repository {} was interrupted; startup recovery records it", runId,
                        repositoryId);
                return null;
            }
            String error = masked(e);
            log.warn("Index run {}: repository {} failed: {}", runId, repositoryId, error);
            runs.recordFailure(runId, repositoryId, error, (System.nanoTime() - startedAt) / 1_000_000);
        }
        clearMarker(repositoryId);
        return null;
    }

    /**
     * Phase 3 for one repository; the marker set in phase 1 is cleared here. Returns the recorded outcome, or null
     * when the run was cancelled or the worker interrupted (nothing recorded).
     */
    RepoIndexOutcome completeSafely(long runId, PreparedRepository repository, boolean reindex,
            ArtifactInstallOutcome install) {
        long repositoryId = repository.repositoryId();
        if (runs.isCancelRequested(runId)) {
            clearMarker(repositoryId);
            return null;
        }
        long startedAt = System.nanoTime();
        RepoIndexOutcome outcome;
        try {
            outcome = indexer.complete(runId, repository, reindex, install, startedAt);
        } catch (Throwable e) {
            if (isInterruption(e, Thread.currentThread().isInterrupted())) {
                log.warn("Index run {}: repository {} was interrupted; startup recovery records it", runId,
                        repositoryId);
                return null;
            }
            String error = masked(e);
            log.warn("Index run {}: repository {} failed: {}", runId, repositoryId, error);
            long duration = (System.nanoTime() - startedAt) / 1_000_000 + repository.prepareMillis();
            runs.recordFailure(runId, repositoryId, error, duration, install);
            outcome = new RepoIndexOutcome(RepoIndexStatus.FAILED, null, null, error, 0, 0, 0, duration);
        }
        clearMarker(repositoryId);
        return outcome;
    }

    /**
     * Phase 2 (spec §3.2): find the providers, including active repositories outside the run that last declared a
     * referenced coordinate, then install them layer by layer. Layers run one after the other and all of them end
     * before the index phase, so a repository's own cross-root installs exist before it is indexed; inside a layer,
     * installs that build a common coordinate, and the members of a cycle, run one after the other, so no artifact
     * is written by two Maven processes at once. A provider outside the run gets a row of its own.
     */
    private Provided provide(ExecutorService executor, long runId, List<Long> runRepositoryIds,
            Map<Long, PreparedRepository> inRun, boolean force) throws InterruptedException {
        Map<Long, PreparedRepository> found = new LinkedHashMap<>(inRun);
        Set<Long> outside = new LinkedHashSet<>();
        // a repository of the run whose preparation failed is not tried again as an outside provider
        Set<Long> attempted = new HashSet<>(runRepositoryIds);
        Map<Gav, List<Long>> declared = coordinates.declaringRepositories();
        while (!runs.isCancelRequested(runId)) {
            Set<Long> wanted = new LinkedHashSet<>();
            for (Gav reference : ArtifactProviders.unmatched(modulesOf(found))) {
                // the first declarer that was not tried yet; if it no longer declares the coordinate, the next one
                declared.getOrDefault(reference, List.of()).stream().filter(id -> !attempted.contains(id))
                        .findFirst().ifPresent(wanted::add);
            }
            if (wanted.isEmpty()) {
                break;
            }
            attempted.addAll(wanted);
            Map<Long, Future<PreparedRepository>> tasks = new LinkedHashMap<>();
            for (Long repositoryId : wanted) {
                tasks.put(repositoryId, executor.submit(() -> prepareOutside(runId, repositoryId)));
            }
            for (Map.Entry<Long, Future<PreparedRepository>> task : tasks.entrySet()) {
                try {
                    PreparedRepository repository = task.getValue().get();
                    if (repository != null) {
                        found.put(task.getKey(), repository);
                        outside.add(task.getKey());
                    }
                } catch (ExecutionException e) {
                    log.warn("Index run {}: provider repository {} could not be prepared: {}", runId, task.getKey(),
                            masked(e.getCause()));
                }
            }
        }
        Map<Long, PreparedRepository> all = inRepositoryOrder(found);
        ArtifactProviders.Plan plan = ArtifactProviders.plan(modulesOf(all));
        Map<Long, ArtifactInstallOutcome> installs = new HashMap<>();
        for (List<Long> layer : plan.layers()) {
            if (runs.isCancelRequested(runId)) {
                break;
            }
            boolean cycle = layer.stream().anyMatch(plan.cyclic()::contains);
            String note = cycle ? "Cyclic dependency between scanned repositories: "
                    + layer.stream().map(id -> all.get(id).slug()).sorted().collect(Collectors.joining(", ")) : null;
            Map<Long, Set<Gav>> built = new HashMap<>();
            for (Long provider : layer) {
                built.put(provider, builtCoordinates(all.get(provider), plan.provisions().get(provider)));
            }
            List<List<Long>> groups = installGroups(layer, built, cycle);
            List<Future<Map<Long, ArtifactInstallOutcome>>> tasks = new ArrayList<>();
            for (List<Long> group : groups) {
                tasks.add(executor.submit(() -> installGroup(runId, group, all, plan, note, force)));
            }
            for (int i = 0; i < tasks.size(); i++) {
                try {
                    installs.putAll(tasks.get(i).get());
                } catch (ExecutionException e) {
                    log.warn("Index run {}: installs of repositories {} could not be finished: {}", runId,
                            groups.get(i), masked(e.getCause()));
                }
            }
        }
        for (Long repositoryId : outside) {
            ArtifactInstallOutcome install = installs.get(repositoryId);
            if (install != null) {
                runs.recordInstall(runId, repositoryId, all.get(repositoryId).commit(), install);
            }
        }
        Map<Long, String> installedCommits = new LinkedHashMap<>();
        all.forEach((id, repository) -> {
            ArtifactInstallOutcome install = installs.get(id);
            if (install != null && install.status() == ArtifactInstallStatus.INSTALLED) {
                installedCommits.put(id, repository.commit());
            }
        });
        return new Provided(installs, installedCommits, plan.transitiveProvidersOf());
    }

    /** One worker's installs, in order; stops when the run is cancelled or the worker interrupted. */
    private Map<Long, ArtifactInstallOutcome> installGroup(long runId, List<Long> group,
            Map<Long, PreparedRepository> all, ArtifactProviders.Plan plan, String cycleNote, boolean force) {
        Map<Long, ArtifactInstallOutcome> installs = new LinkedHashMap<>();
        for (Long provider : group) {
            if (Thread.currentThread().isInterrupted() || runs.isCancelRequested(runId)) {
                break;
            }
            ArtifactInstallOutcome install = installSafely(all.get(provider), plan.provisions().get(provider),
                    cycleNote, force);
            if (install == null) {
                break;
            }
            installs.put(provider, install);
        }
        return installs;
    }

    /** The coordinates the provider's install builds: every module of the project roots it installs. */
    private static Set<Gav> builtCoordinates(PreparedRepository provider, ArtifactProviders.Provision provision) {
        return builtArtifacts(provider, provision).keySet();
    }

    /** Every coordinate the install builds, with its packaging (a consumed coordinate keeps the consumed one). */
    private static Map<Gav, String> builtArtifacts(PreparedRepository provider, ArtifactProviders.Provision provision) {
        Map<Gav, String> built = new LinkedHashMap<>();
        for (MavenModule module : provider.project().modules()) {
            String root = module.projectRoot() == null ? "." : module.projectRoot();
            if (module.gav() != null && provision.roots().contains(root)) {
                built.put(module.gav(), module.packaging());
            }
        }
        built.putAll(provision.consumed());
        return built;
    }

    /**
     * Splits a layer into lists that run in parallel, each one in order: installs that build a common coordinate
     * share a list (two Maven processes never write one artifact at once), and a cycle is a single list.
     */
    static List<List<Long>> installGroups(List<Long> layer, Map<Long, Set<Gav>> built, boolean cycle) {
        if (cycle) {
            return List.of(List.copyOf(layer));
        }
        List<List<Long>> groups = new ArrayList<>();
        List<Set<Gav>> coordinatesOf = new ArrayList<>();
        for (Long provider : layer) {
            Set<Gav> own = built.getOrDefault(provider, Set.of());
            List<Long> members = new ArrayList<>();
            Set<Gav> merged = new HashSet<>(own);
            for (int i = groups.size() - 1; i >= 0; i--) {
                if (!Collections.disjoint(coordinatesOf.get(i), own)) {
                    members.addAll(groups.remove(i));
                    merged.addAll(coordinatesOf.remove(i));
                }
            }
            members.add(provider);
            groups.add(members);
            coordinatesOf.add(merged);
        }
        Comparator<Long> inLayer = Comparator.comparingInt(layer::indexOf);
        groups.forEach(group -> group.sort(inLayer));
        groups.sort(Comparator.comparingInt(group -> layer.indexOf(group.getFirst())));
        return groups.stream().map(List::copyOf).toList();
    }

    /**
     * A provider outside the run is prepared like one in it (Plan 16 Ruling 1). When that fails it gets a run row
     * with a FAILED install (its last_status is not touched, it was not indexed) and null is returned.
     */
    private PreparedRepository prepareOutside(long runId, long repositoryId) {
        if (runs.isCancelRequested(runId)) {
            return null;
        }
        String error;
        try {
            Preparation preparation = indexer.prepare(repositoryId, false);
            if (preparation.prepared() != null) {
                return preparation.prepared();
            }
            error = preparation.finished().error();
        } catch (RuntimeException e) {
            if (isInterruption(e, Thread.currentThread().isInterrupted())) {
                throw e;
            }
            error = masked(e);
        }
        log.warn("Index run {}: provider repository {} could not be checked out: {}", runId, repositoryId, error);
        runs.recordInstall(runId, repositoryId, null,
                new ArtifactInstallOutcome(ArtifactInstallStatus.FAILED, "Checkout failed: " + error));
        return null;
    }

    /**
     * Installs one provider's project roots unless its commit was installed already and every artifact the install builds
     * is in the local repository. A failure never stops the run; null when the worker was interrupted (no outcome).
     */
    ArtifactInstallOutcome installSafely(PreparedRepository provider, ArtifactProviders.Provision provision,
            String cycleNote, boolean force) {
        try {
            boolean upToDate = !force && provider.commit().equals(provider.lastInstalledCommit())
                    && builtArtifacts(provider, provision).entrySet().stream()
                            .allMatch(e -> installer.present(e.getKey(), e.getValue()));
            if (upToDate) {
                return new ArtifactInstallOutcome(ArtifactInstallStatus.UP_TO_DATE, cycleNote);
            }
            String error = installer.install(provider.checkout(), provision.roots());
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            if (error == null) {
                // last_installed_commit is written after the index phase (installedToRecord)
                return new ArtifactInstallOutcome(ArtifactInstallStatus.INSTALLED, cycleNote);
            }
            return new ArtifactInstallOutcome(cycleNote == null ? ArtifactInstallStatus.FAILED
                    : ArtifactInstallStatus.CYCLE_FAILED, cycleNote == null ? error : cycleNote + "\n" + error);
        } catch (RuntimeException e) {
            if (isInterruption(e, Thread.currentThread().isInterrupted())) {
                return null;
            }
            String error = masked(e);
            return new ArtifactInstallOutcome(cycleNote == null ? ArtifactInstallStatus.FAILED
                    : ArtifactInstallStatus.CYCLE_FAILED, cycleNote == null ? error : cycleNote + "\n" + error);
        }
    }

    /** The repositories in project_key, slug order, so provider layers are deterministic (spec §3.2). */
    private Map<Long, PreparedRepository> inRepositoryOrder(Map<Long, PreparedRepository> repositories) {
        Map<Long, PreparedRepository> ordered = new LinkedHashMap<>();
        for (Long id : jdbc.queryForList("SELECT id FROM scm_repository ORDER BY project_key, slug, id", Long.class)) {
            PreparedRepository repository = repositories.get(id);
            if (repository != null) {
                ordered.put(id, repository);
            }
        }
        repositories.forEach(ordered::putIfAbsent);
        return ordered;
    }

    private static Map<Long, List<MavenModule>> modulesOf(Map<Long, PreparedRepository> repositories) {
        Map<Long, List<MavenModule>> modules = new LinkedHashMap<>();
        repositories.forEach((id, repository) -> modules.put(id, repository.project().modules()));
        return modules;
    }

    /** Each repository's recorded outcome; a repository without one (cancelled, interrupted, unrecorded) is left out. */
    private Map<Long, RepoIndexOutcome> await(long runId, Map<Long, Future<RepoIndexOutcome>> tasks)
            throws InterruptedException {
        Map<Long, RepoIndexOutcome> outcomes = new HashMap<>();
        for (Map.Entry<Long, Future<RepoIndexOutcome>> task : tasks.entrySet()) {
            try {
                RepoIndexOutcome outcome = task.getValue().get();
                if (outcome != null) {
                    outcomes.put(task.getKey(), outcome);
                }
            } catch (ExecutionException e) {
                // only reachable when even recording the failure failed (database down)
                log.error("Index run {}: a repository could not be recorded: {}", runId, masked(e.getCause()));
            }
        }
        return outcomes;
    }

    /**
     * Indexes one repository and records an outcome whatever happens, then clears its in-progress marker. An
     * interrupted worker (application shutdown) records nothing and keeps the marker: startup recovery then writes
     * the repository's single INTERRUPTED outcome and keeps its previous index (spec §8).
     */
    void indexSafely(long runId, long repositoryId, boolean force) {
        if (runs.isCancelRequested(runId)) {
            return;
        }
        long startedAt = System.nanoTime();
        try {
            runs.markStarted(runId, repositoryId);
            indexer.index(runId, repositoryId, force);
        } catch (Throwable e) { // a parser StackOverflowError must not leave the repository unrecorded
            if (isInterruption(e, Thread.currentThread().isInterrupted())) {
                log.warn("Index run {}: repository {} was interrupted; startup recovery records it", runId,
                        repositoryId);
                return;
            }
            String error = masked(e);
            log.warn("Index run {}: repository {} failed: {}", runId, repositoryId, error);
            runs.recordFailure(runId, repositoryId, error, (System.nanoTime() - startedAt) / 1_000_000);
        }
        clearMarker(repositoryId);
    }

    /** The outcome is recorded; an interrupt arriving now must not leave the marker (recovery would add a row). */
    private void clearMarker(long repositoryId) {
        boolean interrupted = Thread.interrupted(); // ojdbc fails on an interrupted thread
        try {
            runs.markFinished(repositoryId);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Whether a failure is the thread being interrupted rather than the work failing: the flag is set, or the cause
     * chain holds an InterruptedException (Hikari's connection wait), a ClosedByInterruptException, or an
     * InterruptedIOException other than a socket timeout (ojdbc's "Socket write interrupted").
     */
    static boolean isInterruption(Throwable failure, boolean threadInterrupted) {
        if (threadInterrupted) {
            return true;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof InterruptedException || t instanceof ClosedByInterruptException
                    || t instanceof InterruptedIOException && !(t instanceof SocketTimeoutException)) {
                return true;
            }
        }
        return false;
    }

    private static String masked(Throwable e) {
        return UrlMasking.mask(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
}
