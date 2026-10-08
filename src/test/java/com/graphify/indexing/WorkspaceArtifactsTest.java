package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.impact.ImpactEdge;
import com.graphify.impact.ImpactRequest;
import com.graphify.impact.ImpactService;
import com.graphify.indexer.model.Confidence;
import com.graphify.maven.ArtifactInstaller;
import com.graphify.maven.ArtifactRepositories;
import com.graphify.maven.ClasspathResolver;
import com.graphify.maven.MavenProjectReader;
import com.graphify.repograph.RepoGraphService;
import com.graphify.scm.RepositorySync;
import com.graphify.scm.ScmConnections;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.store.ConnectionPoolSizer;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.store.StoreFixtures;
import com.graphify.store.SymbolCleanup;
import com.graphify.testsupport.AcmeScm;
import com.graphify.testsupport.GitFixtures;
import com.graphify.testsupport.MavenFixtures;
import com.graphify.testsupport.SettingsOverride;
import com.graphify.workspace.GitWorkspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** Spec §8: whole runs over repositories that provide parents and jars to each other, with the real Maven. */
class WorkspaceArtifactsTest extends OracleIntegrationTest {

    private static final Path LOCAL = Path.of(System.getProperty("user.home"), ".m2", "repository");
    private static final Path LIB_JAR = LOCAL.resolve("com/graphify/testfixture/acme/acme-lib/1.0-SNAPSHOT/"
            + "acme-lib-1.0-SNAPSHOT.jar");

    @Autowired IndexRunExecutor executor;
    @Autowired IndexRunRecorder runs;
    @Autowired SecretCipher cipher;
    @Autowired AppSettings settings;
    @Autowired ImpactService impact;
    @Autowired ScmConnections connections;
    @Autowired RepositorySync sync;
    @Autowired RepositoryIndexer indexer;
    @Autowired ConnectionPoolSizer pool;
    @Autowired ModuleCoordinates coordinates;
    @Autowired ArtifactInstaller installer;
    @Autowired SymbolCleanup cleanup;
    @Autowired ArtifactRepositories artifactRepositories;
    @Autowired GitWorkspace workspace;
    @Autowired MavenProjectReader reader;
    @Autowired ClasspathResolver classpathResolver;
    @Autowired RepositoryIndexWriter writer;
    @Autowired RepoGraphService graphs;

    @TempDir
    Path dir;

    private AcmeScm acme;
    private SettingsOverride overrides;

    @BeforeEach
    void setUp() throws Exception {
        StoreFixtures.cleanIndexTables(jdbc);
        jdbc.update("DELETE FROM artifact_repository");
        MavenFixtures.deleteFixtureGroups(LOCAL);
        overrides = new SettingsOverride(settings)
                .set(SettingKeys.INDEX_WORKSPACE_DIR, dir.resolve("ws").toString())
                .set(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY, LOCAL.toString())
                .set(SettingKeys.INDEX_PARALLELISM, "2");
        acme = AcmeScm.create(jdbc, cipher, dir);
    }

    @AfterEach
    void tearDown() throws Exception {
        acme.close();
        overrides.restore();
        MavenFixtures.deleteFixtureGroups(LOCAL);
    }

    private long run(RunScope scope, Long scopeId) {
        long runId = runs.start(RunTrigger.MANUAL, scope, scopeId, "test");
        executor.execute(runId, scope, scopeId, false);
        return runId;
    }

    private Map<String, Object> row(long runId, String slug) {
        return jdbc.queryForMap("SELECT x.status, x.classpath_mode, x.artifact_install, "
                + "DBMS_LOB.SUBSTR(x.artifact_install_error, 4000, 1) AS install_error FROM index_run_repo x "
                + "JOIN scm_repository r ON r.id = x.repo_id WHERE x.run_id = ? AND r.slug = ?", runId, slug);
    }

    private long run(IndexRunExecutor custom, RunScope scope, Long scopeId) {
        long runId = runs.start(RunTrigger.MANUAL, scope, scopeId, "test");
        custom.execute(runId, scope, scopeId, false);
        return runId;
    }

    private IndexRunExecutor executor(RepositoryIndexer withIndexer, IndexRunRecorder withRuns,
            ArtifactInstaller withInstaller) {
        return new IndexRunExecutor(jdbc, connections, sync, withIndexer, withRuns, pool, settings, coordinates,
                withInstaller, cleanup);
    }

    private String installedCommit(String slug) {
        return jdbc.queryForObject("SELECT last_installed_commit FROM scm_repository WHERE slug = ?", String.class,
                slug);
    }

    private long repo(String slug) {
        return jdbc.queryForObject("SELECT id FROM scm_repository WHERE slug = ?", Long.class, slug);
    }

    @Test
    void aParentAndALibraryFromOtherRepositoriesAreInstalledFirst() {
        long runId = run(RunScope.ALL, null);

        assertThat(row(runId, "acme-parent")).containsEntry("STATUS", "SKIPPED_NOT_JAVA")
                .containsEntry("ARTIFACT_INSTALL", "INSTALLED");
        assertThat(row(runId, "acme-lib")).containsEntry("STATUS", "SUCCESS").containsEntry("ARTIFACT_INSTALL", "INSTALLED");
        assertThat(row(runId, "acme-app")).containsEntry("STATUS", "SUCCESS").containsEntry("CLASSPATH_MODE", "FULL")
                .containsEntry("ARTIFACT_INSTALL", null);
        assertThat(LIB_JAR).isRegularFile();
        assertThat(jdbc.queryForObject("SELECT last_installed_commit FROM scm_repository WHERE slug = 'acme-lib'",
                String.class)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE indexing_run_id IS NOT NULL",
                Integer.class)).isZero();
        long greet = jdbc.queryForObject("SELECT id FROM symbol WHERE symbol_key = ?", Long.class,
                "com.graphify.testfixture.acme.lib.Greeter#greet()");
        assertThat(impact.analyze(new ImpactRequest(List.of(greet), null, 1, null, null)).edges())
                .filteredOn(e -> e.level() == 1).extracting(ImpactEdge::confidence).contains(Confidence.EXACT);
    }

    @Test
    void anUnchangedSecondRunIsUpToDateAndADeletedArtifactIsInstalledAgain() throws Exception {
        run(RunScope.ALL, null);
        FileTime installed = Files.getLastModifiedTime(LIB_JAR);

        long second = run(RunScope.ALL, null);
        assertThat(row(second, "acme-lib")).containsEntry("ARTIFACT_INSTALL", "UP_TO_DATE")
                .containsEntry("STATUS", "SKIPPED_UNCHANGED");
        assertThat(row(second, "acme-app")).containsEntry("STATUS", "SKIPPED_UNCHANGED");
        // the duration includes the preparation (ls-remote, checkout, pom reading) even for a skipped repository
        assertThat(jdbc.queryForObject("SELECT x.duration_ms FROM index_run_repo x JOIN scm_repository r "
                + "ON r.id = x.repo_id WHERE x.run_id = ? AND r.slug = 'acme-app'", Long.class, second)).isPositive();
        assertThat(Files.getLastModifiedTime(LIB_JAR)).isEqualTo(installed);

        Files.delete(LIB_JAR);
        long third = run(RunScope.ALL, null);
        assertThat(row(third, "acme-lib")).containsEntry("ARTIFACT_INSTALL", "INSTALLED");
        // the consumer is unchanged, but its provider was installed in this run
        assertThat(row(third, "acme-app")).containsEntry("STATUS", "SUCCESS");
        assertThat(row(third, "acme-gateway")).containsEntry("STATUS", "SKIPPED_UNCHANGED");
        assertThat(LIB_JAR).isRegularFile();
    }

    @Test
    void aDeletedBuiltArtifactThatNoConsumerUsesIsInstalledAgain() throws Exception {
        String parent = "<parent><groupId>" + AcmeScm.GROUP + "</groupId><artifactId>acme-parent</artifactId><version>"
                + AcmeScm.VERSION + "</version><relativePath/></parent>";
        GitFixtures.commit(acme.libBare(), Map.of(
                "pom.xml", MavenFixtures.pom(AcmeScm.GROUP, "acme-lib-root", AcmeScm.VERSION,
                        parent + "<packaging>pom</packaging><modules><module>core</module><module>extra</module></modules>", ""),
                "core/pom.xml", MavenFixtures.pom(AcmeScm.GROUP, "acme-lib", AcmeScm.VERSION, parent, ""),
                "core/src/main/java/com/graphify/testfixture/acme/lib/Greeter.java",
                "package com.graphify.testfixture.acme.lib; public class Greeter { public String greet() { return \"hi\"; } }",
                "extra/pom.xml", MavenFixtures.pom(AcmeScm.GROUP, "acme-lib-extra", AcmeScm.VERSION, parent, ""),
                "extra/src/main/java/acme/extra/Extra.java", "package acme.extra; public class Extra {}"),
                "split into modules");
        run(RunScope.ALL, null);
        Path extraJar = LOCAL.resolve("com/graphify/testfixture/acme/acme-lib-extra/1.0-SNAPSHOT/"
                + "acme-lib-extra-1.0-SNAPSHOT.jar");
        assertThat(extraJar).isRegularFile();
        assertThat(row(run(RunScope.ALL, null), "acme-lib")).containsEntry("ARTIFACT_INSTALL", "UP_TO_DATE");

        Files.delete(extraJar);
        long third = run(RunScope.ALL, null);

        assertThat(row(third, "acme-lib")).containsEntry("ARTIFACT_INSTALL", "INSTALLED");
        assertThat(extraJar).isRegularFile();
    }

    @Test
    void providersOutsideARepositoryScanAreInstalledAndListedInTheRun() throws Exception {
        run(RunScope.ALL, null);
        AcmeScm.deleteLocalGroup(LOCAL);

        long runId = run(RunScope.REPOSITORY, repo("acme-app"));

        assertThat(row(runId, "acme-app")).containsEntry("STATUS", "SUCCESS").containsEntry("CLASSPATH_MODE", "FULL");
        assertThat(row(runId, "acme-lib")).containsEntry("STATUS", "SKIPPED_UNCHANGED")
                .containsEntry("ARTIFACT_INSTALL", "INSTALLED");
        assertThat(row(runId, "acme-parent")).containsEntry("ARTIFACT_INSTALL", "INSTALLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM index_run_repo WHERE run_id = ?", Integer.class, runId))
                .isEqualTo(3);
    }

    @Test
    void aProviderThatDoesNotCompileFailsItsInstallButIsStillIndexed() throws Exception {
        GitFixtures.commit(acme.libBare(), Map.of("src/main/java/com/graphify/testfixture/acme/lib/Broken.java",
                "package com.graphify.testfixture.acme.lib; class Broken { int x = ; }"), "break the build");

        long runId = run(RunScope.ALL, null);

        Map<String, Object> lib = row(runId, "acme-lib");
        assertThat(lib).containsEntry("ARTIFACT_INSTALL", "FAILED").containsEntry("STATUS", "SUCCESS");
        assertThat((String) lib.get("INSTALL_ERROR")).contains("COMPILATION ERROR");
        assertThat(row(runId, "acme-app")).containsEntry("STATUS", "SUCCESS_PARTIAL");
        assertThat(jdbc.queryForObject("SELECT last_installed_commit FROM scm_repository WHERE slug = 'acme-lib'",
                String.class)).isNull();
    }

    @Test
    void aRepositoryWithoutARootPomIndexesItsSubProjects() {
        long runId = run(RunScope.ALL, null);

        assertThat(row(runId, "acme-gateway")).containsEntry("STATUS", "SUCCESS").containsEntry("CLASSPATH_MODE", "FULL");
        assertThat(jdbc.queryForList("SELECT m.path FROM maven_module m JOIN scm_repository r ON r.id = m.repo_id "
                + "WHERE r.slug = 'acme-gateway' ORDER BY m.path", String.class))
                .containsExactlyInAnyOrder("Payment Service", "orders");
    }

    @Test
    void repositoriesThatNeedEachOtherAreACycleWhoseInstallsFailWithANote() throws Exception {
        acme.addCycle();

        long runId = run(RunScope.ALL, null);

        for (String slug : List.of("acme-cycle-a", "acme-cycle-b")) {
            Map<String, Object> member = row(runId, slug);
            assertThat(member).containsEntry("ARTIFACT_INSTALL", "CYCLE_FAILED")
                    .containsEntry("STATUS", "SUCCESS_PARTIAL");
            assertThat((String) member.get("INSTALL_ERROR"))
                    .startsWith("Cyclic dependency between scanned repositories: acme-cycle-a, acme-cycle-b");
        }
        // repositories outside the cycle are not affected
        assertThat(row(runId, "acme-app")).containsEntry("STATUS", "SUCCESS");
    }

    @Test
    void anInstallIsNotRememberedWhenTheRunIsCancelledBeforeItsConsumersAreIndexed() throws Exception {
        run(RunScope.ALL, null);
        String firstInstall = installedCommit("acme-lib");
        GitFixtures.commit(acme.libBare(), Map.of("src/main/java/com/graphify/testfixture/acme/lib/Extra.java",
                "package com.graphify.testfixture.acme.lib; public class Extra {}"), "add Extra");
        // the cancel arrives between the install phase and the index phase
        AtomicBoolean cancelled = new AtomicBoolean();
        IndexRunRecorder cancelling = new IndexRunRecorder(jdbc) {
            @Override
            public boolean isCancelRequested(long runId) {
                return cancelled.get() || super.isCancelRequested(runId);
            }
        };
        ArtifactInstaller cancelAfterLib = new ArtifactInstaller(settings, artifactRepositories) {
            @Override
            public String install(Path checkout, Collection<String> projectRoots) {
                String error = super.install(checkout, projectRoots);
                if (checkout.endsWith("acme-lib")) {
                    cancelled.set(true);
                }
                return error;
            }
        };

        long cancelledRun = runs.start(RunTrigger.MANUAL, RunScope.ALL, null, "test");
        assertThat(executor(indexer, cancelling, cancelAfterLib).execute(cancelledRun, RunScope.ALL, null, false))
                .isEqualTo(RunStatus.CANCELLED);
        assertThat(cancelled).isTrue();
        assertThat(installedCommit("acme-lib")).isEqualTo(firstInstall);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE indexing_run_id IS NOT NULL",
                Integer.class)).isZero();

        long next = run(RunScope.ALL, null);
        assertThat(row(next, "acme-lib")).containsEntry("ARTIFACT_INSTALL", "INSTALLED");
        // unchanged, but re-indexed: its provider was installed again
        assertThat(row(next, "acme-app")).containsEntry("STATUS", "SUCCESS");
        assertThat(installedCommit("acme-lib")).isNotEqualTo(firstInstall);
    }

    @Test
    void anInstallIsNotRememberedWhenAConsumerFailsToIndex() {
        RepositoryIndexer failingApp = new RepositoryIndexer(jdbc, connections, workspace, reader, classpathResolver,
                writer, runs, settings, graphs) {
            @Override
            public RepoIndexOutcome complete(long runId, PreparedRepository prepared, boolean reindex,
                    ArtifactInstallOutcome install, long startedAt) {
                if (prepared.slug().equals("acme-app")) {
                    throw new IllegalStateException("the app could not be indexed");
                }
                return super.complete(runId, prepared, reindex, install, startedAt);
            }
        };

        long runId = run(executor(failingApp, runs, installer), RunScope.ALL, null);

        assertThat(row(runId, "acme-app")).containsEntry("STATUS", "FAILED");
        assertThat(row(runId, "acme-lib")).containsEntry("STATUS", "SUCCESS").containsEntry("ARTIFACT_INSTALL", "INSTALLED");
        assertThat(row(runId, "acme-parent")).containsEntry("ARTIFACT_INSTALL", "INSTALLED");
        // both are needed by the app, whose index failed: the next run installs them again
        assertThat(installedCommit("acme-lib")).isNull();
        assertThat(installedCommit("acme-parent")).isNull();

        long next = run(RunScope.ALL, null);
        assertThat(row(next, "acme-lib")).containsEntry("ARTIFACT_INSTALL", "INSTALLED");
        assertThat(row(next, "acme-app")).containsEntry("STATUS", "SUCCESS");
        assertThat(installedCommit("acme-lib")).isNotNull();
    }

    @Test
    void aProviderOutsideTheRunThatCannotBeCheckedOutGetsAFailedInstallRow() throws Exception {
        run(RunScope.ALL, null);
        MavenFixtures.deleteFixtureGroups(LOCAL);
        jdbc.update("UPDATE scm_repository SET clone_url = 'https://127.0.0.1:1/scm/x.git' WHERE slug = 'acme-lib'");

        long runId = run(RunScope.REPOSITORY, repo("acme-app"));

        Map<String, Object> lib = jdbc.queryForMap("SELECT x.status, x.commit_sha, x.artifact_install, "
                + "DBMS_LOB.SUBSTR(x.artifact_install_error, 4000, 1) AS install_error FROM index_run_repo x "
                + "WHERE x.run_id = ? AND x.repo_id = ?", runId, repo("acme-lib"));
        assertThat(lib).containsEntry("STATUS", "SKIPPED_UNCHANGED").containsEntry("COMMIT_SHA", null)
                .containsEntry("ARTIFACT_INSTALL", "FAILED");
        assertThat((String) lib.get("INSTALL_ERROR")).startsWith("Checkout failed: ");
        // it was not indexed: its own status stays
        assertThat(jdbc.queryForObject("SELECT last_status FROM scm_repository WHERE slug = 'acme-lib'",
                String.class)).isEqualTo("SUCCESS");
        assertThat(row(runId, "acme-parent")).containsEntry("ARTIFACT_INSTALL", "INSTALLED");
        assertThat(row(runId, "acme-app")).containsEntry("STATUS", "SUCCESS_PARTIAL");
    }

    @Test
    void repositoriesOfADisabledConnectionAreNotProviders() throws Exception {
        run(RunScope.ALL, null);
        MavenFixtures.deleteFixtureGroups(LOCAL);
        jdbc.update("INSERT INTO scm_connection (name, type, base_url, secret_enc, enabled) "
                + "VALUES ('acme-off', 'BITBUCKET_DC', 'https://disabled.example', ?, 0)", cipher.encrypt("x"));
        jdbc.update("UPDATE scm_repository SET connection_id = (SELECT id FROM scm_connection WHERE name = 'acme-off') "
                + "WHERE slug = 'acme-lib'");

        assertThat(coordinates.declaringRepositories().values()).noneMatch(ids -> ids.contains(repo("acme-lib")));
        long runId = run(RunScope.REPOSITORY, repo("acme-app"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM index_run_repo WHERE run_id = ? AND repo_id = ?",
                Integer.class, runId, repo("acme-lib"))).isZero();
        assertThat(row(runId, "acme-parent")).containsEntry("ARTIFACT_INSTALL", "INSTALLED");
        assertThat(row(runId, "acme-app")).containsEntry("STATUS", "SUCCESS_PARTIAL");
    }
}
