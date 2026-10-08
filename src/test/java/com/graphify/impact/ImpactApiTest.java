package com.graphify.impact;

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
import org.springframework.http.MediaType;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImpactApiTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @TempDir
    static Path work;

    private long format;

    @BeforeAll
    void load() throws Exception {
        ShopFixture.load(jdbc, writer, work);
        format = ShopFixture.symbolId(jdbc, "com.shop.lib.PriceFormatter#format(int)");
    }

    private String body(String json) {
        return json.replace("FORMAT", Long.toString(format));
    }

    @Test
    void analyzesImpactAsJson() {
        assertThat(mvc.post().uri("/api/v1/impact").contentType(MediaType.APPLICATION_JSON)
                .content(body("{\"symbolIds\":[FORMAT],\"depth\":3}"))).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.summary.repositories").isEqualTo(1);
                    assertThat(json).extractingPath("$.truncated").isEqualTo(false);
                    assertThat(json).extractingPath("$.entryPoints[1].httpPath").isEqualTo("/orders/checkout");
                    assertThat(json).extractingPath("$.entryPoints[1].http").isEqualTo(true);
                    assertThat(json).extractingPath("$.entryPoints[0].http").isEqualTo(false);
                    assertThat(json).extractingPath("$.staleness[0].lastIndexedCommit").isEqualTo("api-1");
                    assertThat(json).extractingPath("$.versionWarnings").asArray().isEmpty();
                    assertThat(json).doesNotHavePath("$.repositories");
                    assertThat(json).extractingPath("$.nodes[0].role").isEqualTo("SEED");
                    assertThat(json).extractingPath("$.nodes[0].confidence").isEqualTo("EXACT");
                    assertThat(json).extractingPath("$.nodes[0].nameOnly").isEqualTo(false);
                    assertThat(json).extractingPath("$.summary.nodesByConfidence.RECOVERED").isEqualTo(0);
                    assertThat(json).extractingPath("$.summary.usagesByConfidence.RECOVERED").isEqualTo(0);
                });
    }

    @Test
    void exportsCsv() {
        assertThat(mvc.post().uri("/api/v1/impact/export?format=csv").contentType(MediaType.APPLICATION_JSON)
                .content(body("{\"symbolIds\":[FORMAT],\"depth\":1,\"confidences\":[\"EXACT\"]}")))
                .hasStatusOk()
                .hasHeader("Content-Disposition", "attachment; filename=\"impact.csv\"")
                .hasContentTypeCompatibleWith("text/csv")
                .bodyText().startsWith("level,repository,module,file,line,from,kind,to,confidence,via_dispatch,snippet")
                .contains("CheckoutService.label(int),CALL,PriceFormatter.format(int),EXACT");
    }

    @Test
    void invalidRequestsAreProblemDetails() {
        assertThat(mvc.post().uri("/api/v1/impact").contentType(MediaType.APPLICATION_JSON)
                .content(body("{\"symbolIds\":[FORMAT],\"depth\":99}"))).hasStatus(400).bodyJson()
                .extractingPath("$.detail").asString().contains("between 1 and 10");
        assertThat(mvc.post().uri("/api/v1/impact").contentType(MediaType.APPLICATION_JSON)
                .content("{\"symbolIds\":[]}")).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/impact").contentType(MediaType.APPLICATION_JSON)
                .content("{\"symbolIds\":[-1]}")).hasStatus(404);
        assertThat(mvc.post().uri("/api/v1/impact").contentType(MediaType.APPLICATION_JSON)
                .content(body("{\"symbolIds\":[FORMAT],\"changeType\":\"SOMETIMES\"}"))).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/impact").contentType(MediaType.APPLICATION_JSON)
                .content("not json")).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/impact/export?format=xlsx").contentType(MediaType.APPLICATION_JSON)
                .content(body("{\"symbolIds\":[FORMAT]}"))).hasStatus(400);
    }

    @Test
    void missingBodiesAndParametersAreBadRequests() {
        assertThat(mvc.post().uri("/api/v1/impact").contentType(MediaType.APPLICATION_JSON)).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/impact").contentType(MediaType.APPLICATION_JSON)
                .content("{\"symbolIds\":null}")).hasStatus(400).bodyJson()
                .extractingPath("$.detail").asString().contains("symbolIds");
        assertThat(mvc.post().uri("/api/v1/impact/export").contentType(MediaType.APPLICATION_JSON)
                .content(body("{\"symbolIds\":[FORMAT]}"))).hasStatus(400);
    }

    @Test
    void openApiDocumentListsTheImpactAndSymbolEndpoints() {
        assertThat(mvc.get().uri("/api/v1/openapi.json")).hasStatusOk().bodyJson().extractingPath("$.paths").asMap()
                .containsKeys("/api/v1/impact", "/api/v1/impact/export", "/api/v1/symbols/search",
                        "/api/v1/symbols/{id}", "/api/v1/symbols/{id}/usages", "/api/v1/repositories");
    }
}
