package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.maven.ArtifactInstaller;
import com.graphify.scm.RepositorySync;
import com.graphify.scm.ScmConnections;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.store.ConnectionPoolSizer;
import com.graphify.store.StoreFixtures;
import com.graphify.store.SymbolCleanup;
import com.graphify.testsupport.MavenFixtures;
import com.graphify.testsupport.SettingsOverride;
import com.graphify.testsupport.ShopScm;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** Real git, Maven and Oracle: the shop fixture behind a fake Bitbucket, indexed by whole runs. */
class IndexRunExecutorTest extends OracleIntegrationTest {

    @Autowired
    IndexRunExecutor executor;

    @Autowired
    IndexRunRecorder runs;

    @Autowired
    RepositorySync sync;

    @Autowired
    ScmConnections connections;

    @Autowired
    SecretCipher cipher;

    @Autowired
    AppSettings settings;

    @TempDir
    Path dir;

    private ShopScm shop;
    private SettingsOverride overrides;

    @BeforeEach
    void setUp() throws Exception {
        StoreFixtures.cleanIndexTables(jdbc);
        overrides = new SettingsOverride(settings)
                .set(SettingKeys.INDEX_WORKSPACE_DIR, dir.resolve("ws").toString())
                .set(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY,
                        Path.of(System.getProperty("user.home"), ".m2", "repository").toString())
                .set(SettingKeys.INDEX_PARALLELISM, "2");
        shop = ShopScm.create(jdbc, cipher, dir);
    }

    @AfterEach
    void tearDown() throws Exception {
        shop.close();
        overrides.restore();
        // runs install shop-lib (a provider of shop-api) into ~/.m2: remove only the fixture groups
        MavenFixtures.deleteFixtureGroups(Path.of(System.getProperty("user.home"), ".m2", "repository"));
    }

    private long start(RunScope scope, Long id) {
        return runs.start(RunTrigger.MANUAL, scope, id, "test");
    }

    private List<String> outcomes(long run) {
        return jdbc.queryForList("""
                SELECT r.slug || ':' || x.status FROM index_run_repo x JOIN scm_repository r ON r.id = x.repo_id
                 WHERE x.run_id = ? ORDER BY r.slug
                """, String.class, run);
    }

    private String connectionStatus() {
        return jdbc.queryForObject("SELECT last_sync_status FROM scm_connection WHERE name = ?", String.class,
                ShopScm.CONNECTION);
    }

    private long repo(String slug) {
        return jdbc.queryForObject("SELECT id FROM scm_repository WHERE slug = ?", Long.class, slug);
    }

    @Test
    void aFullRunSyncsAndIndexesEveryRepositoryThenSkipsThemWhenUnchanged() {
        long run = start(RunScope.ALL, null);

        assertThat(executor.execute(run, RunScope.ALL, null, false)).isEqualTo(RunStatus.SUCCESS);

        assertThat(outcomes(run)).containsExactly("docs:SKIPPED_NOT_JAVA", "shop-api:SUCCESS", "shop-lib:SUCCESS");
        assertThat(runs.status(run)).contains(RunStatus.SUCCESS);
        assertThat(connectionStatus()).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE indexing_run_id IS NOT NULL",
                Integer.class)).isZero();

        long again = start(RunScope.ALL, null);
        executor.execute(again, RunScope.ALL, null, false);

        assertThat(outcomes(again)).containsExactly("docs:SKIPPED_UNCHANGED", "shop-api:SKIPPED_UNCHANGED",
                "shop-lib:SKIPPED_UNCHANGED");
    }

    @Test
    void rejectedCredentialsFailTheConnectionAndTheRun() {
        jdbc.update("UPDATE scm_connection SET secret_enc = ? WHERE name = ?", cipher.encrypt("wrong-token-9"),
                ShopScm.CONNECTION);
        long run = start(RunScope.ALL, null);

        assertThat(executor.execute(run, RunScope.ALL, null, false)).isEqualTo(RunStatus.FAILED);

        assertThat(outcomes(run)).isEmpty();
        assertThat(connectionStatus()).isEqualTo("AUTH_FAILED");
        assertThat(jdbc.queryForObject("SELECT error FROM index_run WHERE id = ?", String.class, run))
                .contains(ShopScm.CONNECTION).contains("AUTH_FAILED").doesNotContain("wrong-token-9");
    }

    @Autowired
    RepositoryIndexer indexer;

    @Autowired
    ConnectionPoolSizer pool;

    @Autowired
    ModuleCoordinates coordinates;

    @Autowired
    ArtifactInstaller installer;

    private void insertOrphan() {
        jdbc.update("""
                INSERT INTO symbol (symbol_key, kind, class_fqn, member_name, display_signature, origin, name_only)
                VALUES ('p.Stale#get/0', 'METHOD', 'p.Stale', 'get', 'p.Stale#get/0', 'BINARY', 1)
                """);
    }

    private int orphans() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM symbol WHERE symbol_key = 'p.Stale#get/0'", Integer.class);
    }

    @Test
    void aRunDeletesTheSymbolsNothingReferencesAnyMore() {
        sync.sync(connections.enabled().getFirst());
        insertOrphan();
        long repository = repo("shop-api");
        long run = start(RunScope.REPOSITORY, repository);

        assertThat(executor.execute(run, RunScope.REPOSITORY, repository, false)).isEqualTo(RunStatus.SUCCESS);

        assertThat(orphans()).isZero();
        // symbols the run indexed are referenced, so they stay
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM symbol", Integer.class)).isPositive();
    }

    @Test
    void aCancelledRunStillDeletesOrphans() {
        sync.sync(connections.enabled().getFirst());
        insertOrphan();
        long repository = repo("docs");
        long run = start(RunScope.REPOSITORY, repository);
        runs.requestCancel(run);

        assertThat(executor.execute(run, RunScope.REPOSITORY, repository, false)).isEqualTo(RunStatus.CANCELLED);

        assertThat(orphans()).isZero();
    }

    @Test
    void aFailingCleanupNeverChangesTheRunsStatus() {
        sync.sync(connections.enabled().getFirst());
        insertOrphan();
        SymbolCleanup failing = new SymbolCleanup(jdbc, settings) {
            @Override
            public int deleteOrphans() {
                throw new IllegalStateException("ORA-00060: deadlock detected");
            }
        };
        IndexRunExecutor withFailingCleanup = new IndexRunExecutor(jdbc, connections, sync, indexer, runs, pool,
                settings, coordinates, installer, failing);
        long repository = repo("docs");
        long run = start(RunScope.REPOSITORY, repository);

        assertThat(withFailingCleanup.execute(run, RunScope.REPOSITORY, repository, false))
                .isEqualTo(RunStatus.SUCCESS);

        assertThat(runs.status(run)).contains(RunStatus.SUCCESS);
        assertThat(orphans()).isEqualTo(1);
    }

    @Test
    void anInterruptDuringCleanupStillWritesTheRunsFinish() {
        sync.sync(connections.enabled().getFirst());
        SymbolCleanup interrupting = new SymbolCleanup(jdbc, settings) {
            @Override
            public int deleteOrphans() {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        };
        IndexRunExecutor withInterruptingCleanup = new IndexRunExecutor(jdbc, connections, sync, indexer, runs, pool,
                settings, coordinates, installer, interrupting);
        long repository = repo("docs");
        long run = start(RunScope.REPOSITORY, repository);

        RunStatus status = withInterruptingCleanup.execute(run, RunScope.REPOSITORY, repository, false);

        boolean flagRestored = Thread.interrupted();
        assertThat(status).isEqualTo(RunStatus.SUCCESS);
        assertThat(runs.status(run)).contains(RunStatus.SUCCESS);
        assertThat(flagRestored).isTrue();
    }

    @Test
    void anInterruptedRunNeverRunsTheCleanup() {
        sync.sync(connections.enabled().getFirst());
        boolean[] called = {false};
        SymbolCleanup recording = new SymbolCleanup(jdbc, settings) {
            @Override
            public int deleteOrphans() {
                called[0] = true;
                return 0;
            }
        };
        IndexRunExecutor withRecordingCleanup = new IndexRunExecutor(jdbc, connections, sync, indexer, runs, pool,
                settings, coordinates, installer, recording);
        long repository = repo("docs");
        long run = start(RunScope.REPOSITORY, repository);

        Thread.currentThread().interrupt();
        RunStatus status = withRecordingCleanup.execute(run, RunScope.REPOSITORY, repository, false);
        boolean flagRestored = Thread.interrupted();

        assertThat(status).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(called[0]).isFalse();
        assertThat(flagRestored).isTrue();
    }

    @Test
    void aRepositoryRunIndexesOnlyThatRepositoryWithoutSyncing() {
        sync.sync(connections.enabled().getFirst());
        long run = start(RunScope.REPOSITORY, repo("docs"));

        assertThat(executor.execute(run, RunScope.REPOSITORY, repo("docs"), false)).isEqualTo(RunStatus.SUCCESS);

        assertThat(outcomes(run)).containsExactly("docs:SKIPPED_NOT_JAVA");
        assertThat(connectionStatus()).isNull();
    }

    @Test
    void aConnectionRunSyncsAndIndexesThatConnection() {
        long run = start(RunScope.CONNECTION, shop.connectionId(jdbc));

        assertThat(executor.execute(run, RunScope.CONNECTION, shop.connectionId(jdbc), false))
                .isEqualTo(RunStatus.SUCCESS);

        assertThat(outcomes(run)).hasSize(3);
        assertThat(connectionStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void aSkippedPurgeAfterARepointIsRecordedOnTheConnectionAndTheRun() {
        executor.execute(start(RunScope.ALL, null), RunScope.ALL, null, false);
        // what a repoint leaves behind: everything inactive but indexed; the new host lists only one repository
        jdbc.update("UPDATE scm_repository SET active = 0, last_indexed_commit = 'c1'");
        shop.bitbucket().removeRepository("SHOP", "docs");
        shop.bitbucket().removeRepository("SHOP", "shop-api");
        long run = start(RunScope.CONNECTION, shop.connectionId(jdbc));

        executor.execute(run, RunScope.CONNECTION, shop.connectionId(jdbc), false);

        assertThat(connectionStatus()).isEqualTo("DEACTIVATION_SKIPPED");
        assertThat(jdbc.queryForObject("SELECT error FROM index_run WHERE id = ?", String.class, run))
                .contains("2 indexed repositories are no longer listed; index purge skipped")
                .contains("scm.max_deactivation_percent");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE last_indexed_commit IS NOT NULL",
                Integer.class)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void aConnectionDisabledAfterTheRunWasAcceptedIsNotSynced() {
        long connection = shop.connectionId(jdbc);
        long run = start(RunScope.CONNECTION, connection);
        jdbc.update("UPDATE scm_connection SET enabled = 0 WHERE id = ?", connection);

        assertThat(executor.execute(run, RunScope.CONNECTION, connection, false)).isEqualTo(RunStatus.SUCCESS);

        assertThat(outcomes(run)).isEmpty();
        assertThat(connectionStatus()).isNull();
        assertThat(jdbc.queryForObject("SELECT error FROM index_run WHERE id = ?", String.class, run))
                .contains("disabled");
    }

    @Test
    void aCancelledRunStartsNothing() {
        long run = start(RunScope.ALL, null);
        runs.requestCancel(run);

        assertThat(executor.execute(run, RunScope.ALL, null, false)).isEqualTo(RunStatus.CANCELLED);

        assertThat(outcomes(run)).isEmpty();
        assertThat(runs.status(run)).contains(RunStatus.CANCELLED);
    }

    @Test
    void anInterruptedRunIsFinishedInterruptedAndTheInterruptIsRestored() {
        long run = start(RunScope.ALL, null);

        Thread.currentThread().interrupt();
        RunStatus status;
        boolean stillInterrupted;
        try {
            status = executor.execute(run, RunScope.ALL, null, false);
        } finally {
            stillInterrupted = Thread.interrupted();
        }

        assertThat(status).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(stillInterrupted).isTrue();
        assertThat(runs.status(run)).contains(RunStatus.INTERRUPTED);
        assertThat(outcomes(run)).isEmpty();
        assertThat(connectionStatus()).isNull();
    }

    @Test
    void anyThrowableIsRecordedAsAFailedRepository() {
        sync.sync(connections.enabled().getFirst());
        long docs = repo("docs");
        jdbc.update("UPDATE scm_repository SET active = 0 WHERE id = ?", docs);
        long run = start(RunScope.REPOSITORY, docs);

        executor.indexSafely(run, docs, false);

        assertThat(outcomes(run)).containsExactly("docs:FAILED");
        assertThat(jdbc.queryForObject("SELECT error FROM index_run_repo WHERE run_id = ?", String.class, run))
                .startsWith("RepositoryNotFoundException");
        assertThat(jdbc.queryForObject("SELECT last_status FROM scm_repository WHERE id = ?", String.class, docs))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT indexing_run_id FROM scm_repository WHERE id = ?", Long.class, docs))
                .isNull();
    }

    @Test
    void aRepositoryWhosePreparationThrowsIsRecordedAsFailed() {
        sync.sync(connections.enabled().getFirst());
        long docs = repo("docs");
        jdbc.update("UPDATE scm_repository SET active = 0 WHERE id = ?", docs);
        long run = start(RunScope.REPOSITORY, docs);

        assertThat(executor.prepareSafely(run, docs, false)).isNull();

        assertThat(outcomes(run)).containsExactly("docs:FAILED");
        assertThat(jdbc.queryForObject("SELECT error FROM index_run_repo WHERE run_id = ?", String.class, run))
                .startsWith("RepositoryNotFoundException");
        assertThat(jdbc.queryForObject("SELECT last_status FROM scm_repository WHERE id = ?", String.class, docs))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT indexing_run_id FROM scm_repository WHERE id = ?", Long.class, docs))
                .isNull();
    }

    @Test
    void aWholeRunRecordsARepositoryWhosePreparationThrowsAndIndexesTheOthers() {
        sync.sync(connections.enabled().getFirst());
        long docs = repo("docs");
        jdbc.update("UPDATE scm_repository SET active = 0 WHERE id = ?", docs);
        long run = start(RunScope.REPOSITORY, docs);

        assertThat(executor.execute(run, RunScope.REPOSITORY, docs, false)).isEqualTo(RunStatus.SUCCESS);

        assertThat(outcomes(run)).containsExactly("docs:FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE indexing_run_id IS NOT NULL",
                Integer.class)).isZero();
    }
}
