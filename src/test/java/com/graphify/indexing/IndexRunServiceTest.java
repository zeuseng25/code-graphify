package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import com.graphify.scm.RepositorySync;
import com.graphify.scm.ScmConnections;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.store.StoreFixtures;
import com.graphify.testsupport.MavenFixtures;
import com.graphify.testsupport.SettingsOverride;
import com.graphify.testsupport.ShopScm;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;

class IndexRunServiceTest extends OracleIntegrationTest {

    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(2);

    @Autowired
    IndexRunService service;

    @Autowired
    IndexRunRecovery recovery;

    @Autowired
    IndexLock lock;

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
                        Path.of(System.getProperty("user.home"), ".m2", "repository").toString());
        shop = ShopScm.create(jdbc, cipher, dir);
        sync.sync(connections.enabled().getFirst());
    }

    @AfterEach
    void tearDown() throws Exception {
        await().atMost(RUN_TIMEOUT).until(() -> lock.holder().isEmpty());
        shop.close();
        overrides.restore();
        // runs install shop-lib (a provider of shop-api) into ~/.m2: remove only the fixture groups
        MavenFixtures.deleteFixtureGroups(Path.of(System.getProperty("user.home"), ".m2", "repository"));
    }

    private long repo(String slug) {
        return jdbc.queryForObject("SELECT id FROM scm_repository WHERE slug = ?", Long.class, slug);
    }

    private int runCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM index_run", Integer.class);
    }

    @Test
    void runsInTheBackgroundAndReleasesTheLock() {
        long run = service.start(RunScope.REPOSITORY, repo("docs"), false, RunTrigger.MANUAL, "tester");

        await().atMost(RUN_TIMEOUT).until(() -> runs.status(run).orElseThrow() != RunStatus.RUNNING);

        assertThat(runs.status(run)).contains(RunStatus.SUCCESS);
        assertThat(jdbc.queryForObject("SELECT trigger_type || ':' || scope || ':' || started_by FROM index_run "
                + "WHERE id = ?", String.class, run)).isEqualTo("MANUAL:REPOSITORY:tester");
        await().atMost(RUN_TIMEOUT).until(() -> lock.holder().isEmpty());
    }

    @Test
    void aSecondRunIsAConflictNamingTheRunInProgress() {
        assertThat(lock.tryAcquire(IndexLock.runHolder(77))).isTrue();
        int before = runCount();
        try {
            assertThatThrownBy(() -> service.start(RunScope.ALL, null, false, RunTrigger.MANUAL, "tester"))
                    .isInstanceOf(IndexRunConflictException.class)
                    .hasMessageContaining("77")
                    .satisfies(e -> assertThat(((ConflictException) e).properties()).containsEntry("runId", 77L));
            assertThat(runCount()).isEqualTo(before);
        } finally {
            lock.release(IndexLock.runHolder(77));
        }
    }

    @Test
    void theCleanupJobHoldingTheLockIsAlsoAConflict() {
        assertThat(lock.tryAcquire(IndexLock.CLEANUP_HOLDER)).isTrue();
        try {
            assertThatThrownBy(() -> service.start(RunScope.ALL, null, false, RunTrigger.MANUAL, "tester"))
                    .isInstanceOf(IndexRunConflictException.class)
                    .hasMessageContaining("cleanup");
        } finally {
            lock.release(IndexLock.CLEANUP_HOLDER);
        }
    }

    @Test
    void validatesTheScopeBeforeTakingTheLock() {
        assertThatThrownBy(() -> service.start(null, null, false, RunTrigger.MANUAL, "t"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.start(RunScope.ALL, 5L, false, RunTrigger.MANUAL, "t"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.start(RunScope.CONNECTION, null, false, RunTrigger.MANUAL, "t"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.start(RunScope.CONNECTION, -1L, false, RunTrigger.MANUAL, "t"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.start(RunScope.REPOSITORY, -1L, false, RunTrigger.MANUAL, "t"))
                .isInstanceOf(NotFoundException.class);

        jdbc.update("UPDATE scm_repository SET active = 0 WHERE slug = 'docs'");
        assertThatThrownBy(() -> service.start(RunScope.REPOSITORY, repo("docs"), false, RunTrigger.MANUAL, "t"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("inactive");

        jdbc.update("UPDATE scm_connection SET enabled = 0");
        assertThatThrownBy(() -> service.start(RunScope.CONNECTION, shop.connectionId(jdbc), false,
                RunTrigger.MANUAL, "t")).isInstanceOf(ConflictException.class).hasMessageContaining("disabled");
        assertThatThrownBy(() -> service.start(RunScope.REPOSITORY, repo("shop-api"), false, RunTrigger.MANUAL, "t"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("disabled");

        assertThat(lock.holder()).isEmpty();
        assertThat(runCount()).isZero();
    }

    @Test
    void cancelRequiresARunningRun() {
        long running = runs.start(RunTrigger.MANUAL, RunScope.ALL, null, "t");

        service.cancel(running);

        assertThat(runs.isCancelRequested(running)).isTrue();
        runs.finish(running, RunStatus.CANCELLED, null);
        assertThatThrownBy(() -> service.cancel(running)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("CANCELLED");
        assertThatThrownBy(() -> service.cancel(-1)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void startupRecoveryRunsOnceTheBeansExistBeforeTheWebServerAcceptsRequests() {
        assertThat(recovery).isInstanceOf(SmartInitializingSingleton.class);
    }

    @Test
    void startupRecoveryInterruptsAbandonedRunsAndFreesTheLock() {
        long abandoned = runs.start(RunTrigger.SCHEDULED, RunScope.ALL, null, "scheduler");
        runs.markStarted(abandoned, repo("shop-api"));
        assertThat(lock.tryAcquire(IndexLock.runHolder(abandoned))).isTrue();

        recovery.recover();

        assertThat(runs.status(abandoned)).contains(RunStatus.INTERRUPTED);
        assertThat(lock.holder()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM index_run_repo WHERE run_id = ?", String.class, abandoned))
                .isEqualTo("INTERRUPTED");
    }
}
