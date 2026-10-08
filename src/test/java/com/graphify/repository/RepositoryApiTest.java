package com.graphify.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.settings.AppSettings;
import com.graphify.store.StoreFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RepositoryApiTest extends OracleIntegrationTest {

    @Autowired
    private AppSettings settings;

    private long alpha;

    @BeforeEach
    void setUp() {
        StoreFixtures.cleanIndexTables(jdbc);
        alpha = StoreFixtures.newRepository(jdbc, "alpha-service");
        StoreFixtures.newRepository(jdbc, "beta_service");
        jdbc.update("INSERT INTO maven_module (repo_id, path, classpath_mode) VALUES (?, 'core', 'FULL')", alpha);
        jdbc.update("UPDATE scm_repository SET last_indexed_commit = 'abc123', last_indexed_at = SYSTIMESTAMP "
                + "WHERE id = ?", alpha);
    }

    @Test
    void listsRepositoriesWithModuleCountsInStableOrder() {
        assertThat(mvc.get().uri("/api/v1/repositories")).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.total").isEqualTo(2);
                    assertThat(json).extractingPath("$.items[0].slug").isEqualTo("alpha-service");
                    assertThat(json).extractingPath("$.items[0].moduleCount").isEqualTo(1);
                    assertThat(json).extractingPath("$.items[0].lastIndexedCommit").isEqualTo("abc123");
                    assertThat(json).extractingPath("$.items[1].slug").isEqualTo("beta_service");
                    assertThat(json).extractingPath("$.size").isEqualTo(50);
                });
    }

    @Test
    void filtersCaseInsensitivelyByText() {
        assertThat(mvc.get().uri("/api/v1/repositories?q=ALPHA")).hasStatusOk().bodyJson()
                .extractingPath("$.items[*].slug").asArray().containsExactly("alpha-service");
    }

    @Test
    void filterTreatsWildcardsLiterally() {
        assertThat(mvc.get().uri("/api/v1/repositories?q=_")).hasStatusOk().bodyJson()
                .extractingPath("$.items[*].slug").asArray().containsExactly("beta_service");
        assertThat(mvc.get().uri("/api/v1/repositories?q=%25")).hasStatusOk().bodyJson()
                .extractingPath("$.total").isEqualTo(0);
        assertThat(mvc.get().uri("/api/v1/repositories?q='")).hasStatusOk().bodyJson()
                .extractingPath("$.total").isEqualTo(0);
    }

    @Test
    void returnsDetailWithModulesOr404() {
        assertThat(mvc.get().uri("/api/v1/repositories/" + alpha)).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.repository.slug").isEqualTo("alpha-service");
                    assertThat(json).extractingPath("$.modules[0].path").isEqualTo("core");
                    assertThat(json).extractingPath("$.modules[0].classpathMode").isEqualTo("FULL");
                });
        assertThat(mvc.get().uri("/api/v1/repositories/-1")).hasStatus(404).bodyJson()
                .extractingPath("$.title").isEqualTo("Not found");
    }

    @Test
    void rejectsInvalidPagingAsProblemDetail() {
        assertThat(mvc.get().uri("/api/v1/repositories?size=100000")).hasStatus(400).bodyJson()
                .extractingPath("$.detail").asString().contains("500");
        assertThat(mvc.get().uri("/api/v1/repositories?page=-1")).hasStatus(400);
        assertThat(mvc.get().uri("/api/v1/repositories?size=abc")).hasStatus(400);
    }

    @Test
    void publishesTheOpenApiDocument() {
        assertThat(mvc.get().uri("/api/v1/openapi.json")).hasStatusOk().bodyJson()
                .extractingPath("$.paths").asMap().containsKey("/api/v1/repositories");
    }

    @Test
    void internalFaultsAreNotMappedTo400() {
        jdbc.update("UPDATE app_setting SET setting_value = 'oops' WHERE setting_key = 'api.page_default_size'");
        reloadSettings();
        try {
            // MockMvc has no container error dispatch: an exception no handler maps (which a real server turns
            // into a 500) is rethrown, whereas a 400 mapping would have produced a response.
            assertThat(mvc.get().uri("/api/v1/repositories")).failure()
                    .hasRootCauseExactlyInstanceOf(IllegalArgumentException.class);
        } finally {
            jdbc.update("UPDATE app_setting SET setting_value = '50' WHERE setting_key = 'api.page_default_size'");
            reloadSettings();
        }
    }

    /** Re-saving a setting with its current value clears the AppSettings cache after commit. */
    private void reloadSettings() {
        settings.update("api.page_max_size", "500", "test");
    }
}
