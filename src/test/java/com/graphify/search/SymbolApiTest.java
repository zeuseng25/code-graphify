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
class SymbolApiTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @TempDir
    static Path work;

    @BeforeAll
    void load() throws Exception {
        ShopFixture.load(jdbc, writer, work);
    }

    @Test
    void searchReturnsAPage() {
        assertThat(mvc.get().uri("/api/v1/symbols/search?q=PriceFormatter.format&size=2")).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.total").isEqualTo(3);
                    assertThat(json).extractingPath("$.items.length()").isEqualTo(2);
                    assertThat(json).extractingPath("$.items[0].key").isEqualTo("com.shop.lib.PriceFormatter#format(int)");
                    assertThat(json).extractingPath("$.items[0].origin").isEqualTo("SOURCE");
                });
    }

    @Test
    void repositoryFilterIsTheRepositoryId() {
        long lib = ShopFixture.repositoryId(jdbc, ShopFixture.LIB_REPO);
        assertThat(mvc.get().uri("/api/v1/symbols/search?q=PriceFormatter.format&repo=" + lib)).hasStatusOk()
                .bodyJson().extractingPath("$.total").isEqualTo(2);
        assertThat(mvc.get().uri("/api/v1/symbols/search?q=PriceFormatter.format&repo=shop-lib")).hasStatus(400);
    }

    @Test
    void invalidSearchInputIsA400ProblemDetail() {
        assertThat(mvc.get().uri("/api/v1/symbols/search")).hasStatus(400);
        assertThat(mvc.get().uri("/api/v1/symbols/search?q={q}", " ")).hasStatus(400).bodyJson()
                .extractingPath("$.title").isEqualTo("Invalid request");
        assertThat(mvc.get().uri("/api/v1/symbols/search?q=x&kind=WIDGET")).hasStatus(400);
    }
}
