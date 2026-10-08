package com.graphify.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SymbolDetailApiTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @TempDir
    static Path work;

    @BeforeAll
    void load() throws Exception {
        ShopFixture.load(jdbc, writer, work);
    }

    @Test
    void servesDetailUsagesAndSummary() {
        long format = ShopFixture.symbolId(jdbc, "com.shop.lib.PriceFormatter#format(int)");

        assertThat(mvc.get().uri("/api/v1/symbols/" + format)).hasStatusOk().bodyJson()
                .extractingPath("$.parent.key").isEqualTo("com.shop.lib.PriceFormatter");
        assertThat(mvc.get().uri("/api/v1/symbols/" + format + "/usages?confidence=EXACT,RECOVERED")).hasStatusOk()
                .bodyJson().extractingPath("$.items[0].from.key").isEqualTo("com.shop.api.CheckoutService#label(int)");
        assertThat(mvc.get().uri("/api/v1/symbols/" + format + "/usages/summary")).hasStatusOk().bodyJson()
                .extractingPath("$.byRepository[0].repository.slug").isEqualTo("shop-api");
        long apiRepo = ShopFixture.repositoryId(jdbc, ShopFixture.API_REPO);
        long libRepo = ShopFixture.repositoryId(jdbc, ShopFixture.LIB_REPO);
        assertThat(mvc.get().uri("/api/v1/symbols/" + format + "/usages?repo=" + apiRepo)).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.total").isEqualTo(1);
                    assertThat(json).extractingPath("$.items[0].repository.id").isEqualTo((int) apiRepo);
                    assertThat(json).extractingPath("$.items[0].repository.projectKey").isEqualTo("TEST");
                });
        assertThat(mvc.get().uri("/api/v1/symbols/" + format + "/usages?repo=" + libRepo)).hasStatusOk().bodyJson()
                .extractingPath("$.total").isEqualTo(0);
        assertThat(mvc.get().uri("/api/v1/symbols/" + format + "/usages?repo=shop-api")).hasStatus(400);
    }

    @Test
    void unknownIdsAndBadFiltersAreProblemDetails() {
        assertThat(mvc.get().uri("/api/v1/symbols/-1")).hasStatus(404);
        assertThat(mvc.get().uri("/api/v1/symbols/-1/usages")).hasStatus(404);
        assertThat(mvc.get().uri("/api/v1/symbols/-1/usages/summary")).hasStatus(404);
        long format = ShopFixture.symbolId(jdbc, "com.shop.lib.PriceFormatter#format(int)");
        assertThat(mvc.get().uri("/api/v1/symbols/" + format + "/usages?confidence=MAYBE")).hasStatus(400);
    }
}
