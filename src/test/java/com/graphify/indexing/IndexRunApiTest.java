package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.StoreFixtures;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class IndexRunApiTest extends OracleIntegrationTest {

    @Autowired
    IndexRunRecorder runs;

    @Autowired
    IndexLock lock;

    private long alpha;
    private long beta;
    private long finished;

    @BeforeEach
    void setUp() {
        StoreFixtures.cleanIndexTables(jdbc);
        alpha = StoreFixtures.newRepository(jdbc, "alpha");
        beta = StoreFixtures.newRepository(jdbc, "beta");
        finished = runs.start(RunTrigger.MANUAL, RunScope.ALL, null, "tester");
        runs.record(finished, alpha, new RepoIndexOutcome(RepoIndexStatus.SUCCESS, "abc", "FULL", null, 10, 20, 1, 1500));
        runs.record(finished, beta, new RepoIndexOutcome(RepoIndexStatus.CLONE_FAILED, null, null,
                "GitException: clone https://***@scm/x.git failed", 0, 0, 0, 30));
        runs.finish(finished, RunStatus.SUCCESS, null);
        jdbc.update("UPDATE scm_repository SET last_status = 'SUCCESS' WHERE id = ?", alpha);
        jdbc.update("UPDATE scm_repository SET last_status = 'CLONE_FAILED' WHERE id = ?", beta);
    }

    @AfterEach
    void tearDown() {
        await().atMost(Duration.ofMinutes(1)).until(() -> lock.holder().isEmpty());
    }

    @Test
    void showsARunWithItsRepositoryOutcomes() {
        assertThat(mvc.get().uri("/api/v1/index/runs/" + finished)).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.run.status").isEqualTo("SUCCESS");
            assertThat(json).extractingPath("$.run.startedBy").isEqualTo("tester");
            assertThat(json).extractingPath("$.run.scopeId").isNull();
            assertThat(json).extractingPath("$.repositoriesByStatus.SUCCESS").isEqualTo(1);
            assertThat(json).extractingPath("$.repositoriesByStatus.CLONE_FAILED").isEqualTo(1);
            assertThat(json).extractingPath("$.repositories[0].repository.slug").isEqualTo("alpha");
            assertThat(json).extractingPath("$.repositories[0].usageCount").isEqualTo(20);
            assertThat(json).extractingPath("$.repositories[1].error").asString().contains("***@scm");
        });
        assertThat(mvc.get().uri("/api/v1/index/runs/-1")).hasStatus(404);
    }

    @Test
    void listsRunsNewestFirst() {
        long newer = runs.start(RunTrigger.SCHEDULED, RunScope.ALL, null, "scheduler");

        assertThat(mvc.get().uri("/api/v1/index/runs")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.items[0].id").isEqualTo((int) newer);
            assertThat(json).extractingPath("$.items[0].status").isEqualTo("RUNNING");
            assertThat(json).extractingPath("$.items[0].scopeId").isNull();
            assertThat(json).extractingPath("$.total").isEqualTo(2);
        });
    }

    @Test
    void listsARepositorysRunHistory() {
        assertThat(mvc.get().uri("/api/v1/repositories/" + alpha + "/runs")).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.items[0].status").isEqualTo("SUCCESS");
                    assertThat(json).extractingPath("$.items[0].commit").isEqualTo("abc");
                    assertThat(json).extractingPath("$.items[0].runId").isEqualTo((int) finished);
                });
        assertThat(mvc.get().uri("/api/v1/repositories/-1/runs")).hasStatus(404);
    }

    @Test
    void filtersRepositoriesByLastStatus() {
        assertThat(mvc.get().uri("/api/v1/repositories?status=clone_failed")).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.total").isEqualTo(1);
                    assertThat(json).extractingPath("$.items[0].slug").isEqualTo("beta");
                });
        assertThat(mvc.get().uri("/api/v1/repositories?status=NOPE")).hasStatus(400);
    }

    @Test
    void aRunInProgressIsAConflictCarryingItsId() {
        assertThat(lock.tryAcquire(IndexLock.runHolder(finished))).isTrue();
        try {
            assertThat(mvc.post().uri("/api/v1/index/runs").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"scope\":\"ALL\"}")).hasStatus(409).bodyJson().satisfies(json -> {
                        assertThat(json).extractingPath("$.title").isEqualTo("Conflict");
                        assertThat(json).extractingPath("$.runId").isEqualTo((int) finished);
                    });
        } finally {
            lock.release(IndexLock.runHolder(finished));
        }
    }

    @Test
    void rejectsInvalidStartRequests() {
        assertThat(mvc.post().uri("/api/v1/index/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"REPOSITORY\"}")).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/index/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"NOPE\"}")).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/index/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/index/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"REPOSITORY\",\"id\":-1}")).hasStatus(404);
    }

    @Test
    void startsARunAndPointsToIt() throws Exception {
        MvcTestResult result = mvc.post().uri("/api/v1/index/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"REPOSITORY\",\"id\":" + alpha + "}").exchange();

        assertThat(result).hasStatus(202);
        Number runId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.runId");
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/index/runs/" + runId);
        await().atMost(Duration.ofMinutes(1)).until(() -> lock.holder().isEmpty());
        assertThat(mvc.get().uri("/api/v1/index/runs/" + runId)).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.run.status").isEqualTo("SUCCESS");
            assertThat(json).extractingPath("$.run.startedBy").isEqualTo("tester");
            assertThat(json).extractingPath("$.run.scopeId").isEqualTo((int) alpha);
            assertThat(json).extractingPath("$.repositories[0].status").isEqualTo("CLONE_FAILED");
        });
    }

    @Test
    void showsAProviderRepositorysArtifactInstall() {
        long gamma = StoreFixtures.newRepository(jdbc, "gamma");
        long withInstall = runs.start(RunTrigger.MANUAL, RunScope.ALL, null, "tester");
        runs.record(withInstall, alpha, new RepoIndexOutcome(RepoIndexStatus.SUCCESS, "abc", "FULL", null, 10, 20, 1, 1500));
        runs.record(withInstall, gamma, new RepoIndexOutcome(RepoIndexStatus.SUCCESS, "def", "FULL", null, 1, 1, 0, 10),
                new ArtifactInstallOutcome(ArtifactInstallStatus.FAILED, "Maven exited with 1:\n[ERROR] boom"));
        runs.finish(withInstall, RunStatus.SUCCESS, null);

        assertThat(mvc.get().uri("/api/v1/index/runs/" + withInstall)).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.repositories[?(@.repository.slug == 'gamma')].artifactInstall").asArray()
                    .containsExactly("FAILED");
            assertThat(json).extractingPath("$.repositories[?(@.repository.slug == 'gamma')].artifactInstallError")
                    .asArray().singleElement().asString().contains("[ERROR] boom");
            assertThat(json).extractingPath("$.repositories[?(@.repository.slug == 'alpha')]").asArray().hasSize(1);
            assertThat(json).extractingPath("$.repositories[?(@.repository.slug == 'alpha')].artifactInstall").asArray()
                    .allMatch(java.util.Objects::isNull);
        });
    }

    @Test
    void aLongMultibyteInstallOutputIsCutAndNeverFailsTheEndpoints() {
        long gamma = StoreFixtures.newRepository(jdbc, "gamma");
        long withInstall = runs.start(RunTrigger.MANUAL, RunScope.ALL, null, "tester");
        runs.record(withInstall, gamma, new RepoIndexOutcome(RepoIndexStatus.SUCCESS, "def", "FULL", null, 1, 1, 0, 10),
                new ArtifactInstallOutcome(ArtifactInstallStatus.FAILED, "ç".repeat(5000) + "ğ".repeat(3000)));
        runs.finish(withInstall, RunStatus.SUCCESS, null);

        assertThat(mvc.get().uri("/api/v1/index/runs/" + withInstall)).hasStatusOk().bodyJson().satisfies(json ->
                assertThat(json).extractingPath("$.repositories[0].artifactInstallError").asString()
                        .hasSize(4000).startsWith("çç").doesNotContain("ğ"));
        assertThat(mvc.get().uri("/api/v1/repositories/" + gamma + "/runs")).hasStatusOk().bodyJson().satisfies(json ->
                assertThat(json).extractingPath("$.items[0].artifactInstallError").asString().hasSize(4000));
    }

    @Test
    void theCutNeverSplitsASurrogatePair() {
        long delta = StoreFixtures.newRepository(jdbc, "delta");
        long withInstall = runs.start(RunTrigger.MANUAL, RunScope.ALL, null, "tester");
        // 3999 chars, then an emoji (two chars) straddling the 4000-char cut
        runs.record(withInstall, delta, new RepoIndexOutcome(RepoIndexStatus.SUCCESS, "def", "FULL", null, 1, 1, 0, 10),
                new ArtifactInstallOutcome(ArtifactInstallStatus.FAILED, "a".repeat(3999) + "😀" + "b".repeat(100)));
        runs.finish(withInstall, RunStatus.SUCCESS, null);

        assertThat(mvc.get().uri("/api/v1/index/runs/" + withInstall)).hasStatusOk().bodyJson().satisfies(json ->
                assertThat(json).extractingPath("$.repositories[0].artifactInstallError").asString()
                        .isEqualTo("a".repeat(3999)));
    }

    @Test
    void cancelsOnlyARunningRun() {
        long running = runs.start(RunTrigger.MANUAL, RunScope.ALL, null, "t");

        assertThat(mvc.post().uri("/api/v1/index/runs/" + running + "/cancel")).hasStatus(202);
        assertThat(runs.isCancelRequested(running)).isTrue();
        assertThat(mvc.post().uri("/api/v1/index/runs/" + finished + "/cancel")).hasStatus(409);
        assertThat(mvc.post().uri("/api/v1/index/runs/-1/cancel")).hasStatus(404);
        runs.finish(running, RunStatus.CANCELLED, null);
    }
}
