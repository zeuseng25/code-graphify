package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.impact.ImpactEdge;
import com.graphify.impact.ImpactRequest;
import com.graphify.impact.ImpactResult;
import com.graphify.impact.ImpactService;
import com.graphify.indexer.model.Confidence;
import com.graphify.scm.RepositorySync;
import com.graphify.scm.ScmConnections;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.store.StoreFixtures;
import com.graphify.testsupport.GitFixtures;
import com.graphify.testsupport.MavenFixtures;
import com.graphify.testsupport.SettingsOverride;
import com.graphify.testsupport.ShopScm;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Spec §11 acceptance: a fake Bitbucket lists two git repositories (Maven projects), the pipeline clones, resolves
 * the classpath through a file-based Maven repository, indexes and writes them, and impact analysis then finds the
 * cross-repository caller EXACTly.
 */
class RepositoryIndexerTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexer indexer;

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

    @Autowired
    ImpactService impact;

    @TempDir
    Path dir;

    private ShopScm shop;
    private SettingsOverride overrides;
    private long runId;

    @BeforeEach
    void setUp() throws Exception {
        StoreFixtures.cleanIndexTables(jdbc);
        overrides = new SettingsOverride(settings)
                .set(SettingKeys.INDEX_WORKSPACE_DIR, dir.resolve("ws").toString())
                .set(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY,
                        Path.of(System.getProperty("user.home"), ".m2", "repository").toString());
        shop = ShopScm.create(jdbc, cipher, dir);
        sync.sync(connections.enabled().getFirst());
        runId = runs.start(RunTrigger.MANUAL, RunScope.ALL, null, "test");
    }

    @AfterEach
    void tearDown() throws Exception {
        shop.close();
        overrides.restore();
        MavenFixtures.deleteFixtureGroups(Path.of(System.getProperty("user.home"), ".m2", "repository"));
    }

    private long repo(String slug) {
        return jdbc.queryForObject("SELECT id FROM scm_repository WHERE slug = ?", Long.class, slug);
    }

    private long symbol(String key) {
        return jdbc.queryForObject("SELECT id FROM symbol WHERE symbol_key = ?", Long.class, key);
    }

    /** A repository has one outcome per run, so a repeated scan of it is a run of its own. */
    private long newRun() {
        return runs.start(RunTrigger.MANUAL, RunScope.ALL, null, "test");
    }

    @Test
    void indexesRepositoriesEndToEndAndImpactFindsTheCrossRepositoryCaller() {
        RepoIndexOutcome lib = indexer.index(runId, repo("shop-lib"), false);
        RepoIndexOutcome api = indexer.index(runId, repo("shop-api"), false);

        assertThat(lib.status()).isEqualTo(RepoIndexStatus.SUCCESS);
        assertThat(api.status()).isEqualTo(RepoIndexStatus.SUCCESS);
        assertThat(api.classpathMode()).isEqualTo("FULL");
        assertThat(api.usages()).isPositive();
        assertThat(jdbc.queryForList("SELECT d.group_id || ':' || d.artifact_id || ':' || d.version FROM module_dependency d",
                String.class)).containsExactly("com.graphify.testfixture.shop:shop-lib:1.0.0");
        assertThat(jdbc.queryForList("SELECT DISTINCT packaging FROM maven_module", String.class)).containsExactly("jar");

        ImpactResult result = impact.analyze(new ImpactRequest(
                List.of(symbol("com.shop.lib.PriceFormatter#format(int)")), null, 1, null, null));
        assertThat(result.edges()).filteredOn(e -> e.level() == 1).extracting(ImpactEdge::confidence)
                .contains(Confidence.EXACT);
        assertThat(jdbc.queryForList("SELECT status FROM index_run_repo WHERE run_id = ? ORDER BY id", String.class,
                runId)).containsExactly("SUCCESS", "SUCCESS");
        // every indexed repository has its graph analysis for the commit it was indexed at
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository r JOIN repo_graph_analysis a "
                + "ON a.repo_id = r.id AND a.commit_hash = r.last_indexed_commit", Integer.class))
                .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE last_indexed_commit IS NOT NULL",
                        Integer.class)).isPositive();
    }

    @Test
    void unchangedRepositoriesAreSkippedAndNewCommitsAreReindexed() throws IOException {
        long api = repo("shop-api");
        String first = indexer.index(runId, api, false).commit();

        assertThat(indexer.index(newRun(), api, false).status()).isEqualTo(RepoIndexStatus.SKIPPED_UNCHANGED);

        String second = GitFixtures.commit(shop.apiBare(), Map.of("src/main/java/com/shop/api/Extra.java", """
                package com.shop.api;
                import com.shop.lib.PriceFormatter;
                class Extra { String x() { return new PriceFormatter().format(7); } }
                """), "add Extra");
        RepoIndexOutcome again = indexer.index(newRun(), api, false);

        assertThat(again.status()).isEqualTo(RepoIndexStatus.SUCCESS);
        assertThat(again.commit()).isEqualTo(second).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM symbol WHERE symbol_key = 'com.shop.api.Extra#x()'",
                Integer.class)).isEqualTo(1);
        assertThat(indexer.index(newRun(), api, true).status()).isEqualTo(RepoIndexStatus.SUCCESS);
    }

    @Test
    void aMissingGraphAnalysisIsRedoneAtTheSameCommit() {
        long api = repo("shop-api");
        String commit = indexer.index(runId, api, false).commit();
        for (String table : List.of("repo_graph_cycle", "repo_graph_community", "repo_graph_metric",
                "repo_graph_analysis")) {
            jdbc.update("DELETE FROM " + table + " WHERE repo_id = ?", api);
        }

        assertThat(indexer.index(newRun(), api, false).status()).isEqualTo(RepoIndexStatus.SKIPPED_UNCHANGED);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repo_graph_analysis WHERE repo_id = ? AND commit_hash = ?",
                Integer.class, api, commit)).isEqualTo(1);
    }

    @Test
    void aRepositoryWithoutJavaIsSkipped() {
        assertThat(indexer.index(runId, repo("docs"), false).status()).isEqualTo(RepoIndexStatus.SKIPPED_NOT_JAVA);
        assertThat(jdbc.queryForObject("SELECT last_status FROM scm_repository WHERE slug = 'docs'", String.class))
                .isEqualTo("SKIPPED_NOT_JAVA");
    }

    @Test
    void anUnresolvableClasspathStillIndexesButIsPartial() {
        jdbc.update("UPDATE artifact_repository SET enabled = 0");
        overrides.set(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY, dir.resolve("empty-m2").toString());
        overrides.set(SettingKeys.INDEX_MAVEN_TIMEOUT, "PT60S");

        RepoIndexOutcome api = indexer.index(runId, repo("shop-api"), false);

        assertThat(api.status()).isEqualTo(RepoIndexStatus.SUCCESS_PARTIAL);
        assertThat(api.classpathMode()).isEqualTo("NONE");
        assertThat(api.error()).isNotBlank();
        assertThat(api.usages()).isPositive();
    }

    @Test
    void cloneFailureIsRecordedWithoutCredentials() {
        jdbc.update("UPDATE scm_repository SET clone_url = 'https://bob:hunter2@127.0.0.1:1/scm/x.git' "
                + "WHERE slug = 'shop-api'");

        RepoIndexOutcome outcome = indexer.index(runId, repo("shop-api"), false);

        assertThat(outcome.status()).isEqualTo(RepoIndexStatus.CLONE_FAILED);
        assertThat(outcome.error()).doesNotContain("hunter2").doesNotContain(ShopScm.TOKEN);
        assertThat(jdbc.queryForObject("SELECT error FROM index_run_repo WHERE run_id = ? AND status = 'CLONE_FAILED'",
                String.class, runId)).doesNotContain("hunter2").doesNotContain(ShopScm.TOKEN);
        assertThat(jdbc.queryForObject("SELECT last_status FROM scm_repository WHERE slug = 'shop-api'", String.class))
                .isEqualTo("CLONE_FAILED");
    }

    private int count(String sql, long repositoryId) {
        return jdbc.queryForObject(sql, Integer.class, repositoryId);
    }

    private int usages(long repositoryId) {
        return count("SELECT COUNT(*) FROM usage u JOIN maven_module m ON m.id = u.module_id WHERE m.repo_id = ?",
                repositoryId);
    }

    private int declarations(long repositoryId) {
        return count("SELECT COUNT(*) FROM symbol_declaration d JOIN maven_module m ON m.id = d.module_id "
                + "WHERE m.repo_id = ?", repositoryId);
    }

    private int modules(long repositoryId) {
        return count("SELECT COUNT(*) FROM maven_module WHERE repo_id = ?", repositoryId);
    }

    @Test
    void aPartialIndexIsRetriedOnTheSameCommitOnceTheClasspathResolves() {
        long api = repo("shop-api");
        jdbc.update("UPDATE artifact_repository SET enabled = 0");
        overrides.set(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY, dir.resolve("empty-m2").toString());
        overrides.set(SettingKeys.INDEX_MAVEN_TIMEOUT, "PT60S");
        RepoIndexOutcome partial = indexer.index(runId, api, false);
        assertThat(partial.status()).isEqualTo(RepoIndexStatus.SUCCESS_PARTIAL);

        jdbc.update("UPDATE artifact_repository SET enabled = 1");
        overrides.set(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY,
                Path.of(System.getProperty("user.home"), ".m2", "repository").toString());
        RepoIndexOutcome again = indexer.index(newRun(), api, false);

        assertThat(again.status()).isEqualTo(RepoIndexStatus.SUCCESS);
        assertThat(again.classpathMode()).isEqualTo("FULL");
        assertThat(again.commit()).isEqualTo(partial.commit());
        assertThat(indexer.index(newRun(), api, false).status()).isEqualTo(RepoIndexStatus.SKIPPED_UNCHANGED);
    }

    @Test
    void aCommitWithoutJavaClearsTheIndexAndIsNotRescanned() throws IOException {
        long api = repo("shop-api");
        assertThat(indexer.index(runId, api, false).status()).isEqualTo(RepoIndexStatus.SUCCESS);
        assertThat(usages(api)).isPositive();

        String noJava = GitFixtures.remove(shop.apiBare(), shop.apiJavaFiles(), "drop the sources");
        RepoIndexOutcome outcome = indexer.index(newRun(), api, false);

        assertThat(outcome.status()).isEqualTo(RepoIndexStatus.SKIPPED_NOT_JAVA);
        assertThat(outcome.commit()).isEqualTo(noJava);
        assertThat(usages(api)).isZero();
        assertThat(declarations(api)).isZero();
        // the pom's coordinates stay, so the repository can still provide it
        assertThat(modules(api)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_indexed_commit FROM scm_repository WHERE id = ?", String.class, api))
                .isEqualTo(noJava);
        assertThat(indexer.index(newRun(), api, false).status()).isEqualTo(RepoIndexStatus.SKIPPED_UNCHANGED);
    }

    @Test
    void aFailedRunKeepsThePreviousIndex() {
        long api = repo("shop-api");
        assertThat(indexer.index(runId, api, false).status()).isEqualTo(RepoIndexStatus.SUCCESS);
        int usagesBefore = usages(api);
        int declarationsBefore = declarations(api);
        assertThat(usagesBefore).isPositive();
        assertThat(declarationsBefore).isPositive();
        jdbc.update("UPDATE scm_repository SET clone_url = 'https://127.0.0.1:1/scm/x.git' WHERE id = ?", api);

        assertThat(indexer.index(newRun(), api, true).status()).isEqualTo(RepoIndexStatus.CLONE_FAILED);

        assertThat(usages(api)).isEqualTo(usagesBefore);
        assertThat(declarations(api)).isEqualTo(declarationsBefore);
        assertThat(modules(api)).isEqualTo(1);
    }
}
