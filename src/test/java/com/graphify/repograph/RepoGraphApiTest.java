package com.graphify.repograph;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.store.StoreFixtures;
import com.graphify.testsupport.GraphFixture;
import com.graphify.testsupport.SettingsOverride;
import com.graphify.testsupport.ShopFixture;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class RepoGraphApiTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @Autowired
    RepoGraphService graphs;

    @Autowired
    AppSettings settings;

    @TempDir
    Path work;

    @Test
    void showsThePackageGraphByDefaultAndClassesWithTheirMetrics() {
        long repo = GraphFixture.load(jdbc, writer);
        graphs.analyze(repo, GraphFixture.COMMIT);

        assertThat(mvc.get().uri("/api/v1/repositories/" + repo + "/graph")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.level").isEqualTo("PACKAGE");
            assertThat(json).extractingPath("$.nodes.length()").isEqualTo(4);
            assertThat(json).extractingPath("$.truncated").isEqualTo(false);
        });
        assertThat(mvc.get().uri("/api/v1/repositories/" + repo + "/graph?level=class&focus=com.g.a")).hasStatusOk()
                .bodyJson().satisfies(json -> {
                    assertThat(json).extractingPath("$.nodes[*].id").asArray()
                            .containsExactly("class:com.g.a.Alpha", "class:com.g.a.AlphaHelper");
                    assertThat(json).extractingPath("$.nodes[1].metrics.dependents").isEqualTo(5);
                });
    }

    @Test
    void theMethodLevelShowsCallersAndCallees() {
        long repo = GraphFixture.load(jdbc, writer);

        assertThat(mvc.get().uri("/api/v1/repositories/" + repo + "/graph?level=METHOD&focus=com.g.a.Alpha"))
                .hasStatusOk().bodyJson().satisfies(json -> {
                    assertThat(json).extractingPath("$.nodes[*].id").asArray().contains("member:com.g.a.Alpha#run()",
                            "member:com.g.a.AlphaHelper#one()", "member:com.g.c.Gamma#total()");
                    assertThat(json).extractingPath("$.edges[?(@.from == 'member:com.g.a.Alpha#run()' "
                            + "&& @.to == 'member:com.g.a.AlphaHelper#one()')].kinds.CALL").asArray()
                            .containsExactly(1);
                });
    }

    @Test
    void otherRepositoriesAppearAsExternalNodesOnlyWhenAsked() throws Exception {
        ShopFixture.load(jdbc, writer, work);
        long api = ShopFixture.repositoryId(jdbc, ShopFixture.API_REPO);

        assertThat(mvc.get().uri("/api/v1/repositories/" + api + "/graph?includeExternal=true")).hasStatusOk()
                .bodyJson().extractingPath("$.nodes[?(@.type == 'EXTERNAL_REPOSITORY')].id").asArray()
                .containsExactly("external:repo:TEST/shop-lib");
        assertThat(mvc.get().uri("/api/v1/repositories/" + api + "/graph")).hasStatusOk().bodyJson()
                .extractingPath("$.nodes[?(@.type == 'EXTERNAL_REPOSITORY')]").asArray().isEmpty();
    }

    @Test
    void rejectsBadLevelsFocusesAndRepositories() {
        long repo = GraphFixture.load(jdbc, writer);
        String base = "/api/v1/repositories/" + repo + "/graph";

        assertThat(mvc.get().uri(base + "?level=METHOD")).hasStatus(400);
        assertThat(mvc.get().uri(base + "?level=METHOD&focus=com.nowhere.Nope")).hasStatus(404);
        assertThat(mvc.get().uri(base + "?level=METHOD&focus=java.lang.String")).hasStatus(404);
        assertThat(mvc.get().uri(base + "?level=FILE")).hasStatus(400);
        assertThat(mvc.get().uri("/api/v1/repositories/999999/graph")).hasStatus(404);
        assertThat(anonymous().get().uri(base)).hasStatus(401);
    }

    @Test
    void trailingDotsAreIgnoredAndAnUnknownPackageIs404() {
        long repo = GraphFixture.load(jdbc, writer);
        String base = "/api/v1/repositories/" + repo + "/graph";

        assertThat(mvc.get().uri(base + "?level=CLASS&focus=com.g.a.")).hasStatusOk().bodyJson()
                .extractingPath("$.nodes[*].id").asArray()
                .containsExactly("class:com.g.a.Alpha", "class:com.g.a.AlphaHelper");
        assertThat(mvc.get().uri(base + "?level=CLASS&focus=com.nowhere")).hasStatus(404);
        assertThat(mvc.get().uri(base + "?level=PACKAGE&focus=com.nowhere")).hasStatus(404);
    }

    @Test
    void theMethodLevelStillShowsExternalRepositoriesAndCanRollUp() throws Exception {
        ShopFixture.load(jdbc, writer, work);
        long api = ShopFixture.repositoryId(jdbc, ShopFixture.API_REPO);
        String base = "/api/v1/repositories/" + api + "/graph?level=METHOD&focus=com.shop.api.CheckoutService";

        assertThat(mvc.get().uri(base + "&includeExternal=true")).hasStatusOk().bodyJson()
                .extractingPath("$.nodes[?(@.type == 'EXTERNAL_REPOSITORY')].id").asArray()
                .containsExactly("external:repo:TEST/shop-lib");
        assertThat(mvc.get().uri(base)).hasStatusOk().bodyJson()
                .extractingPath("$.nodes[?(@.type == 'EXTERNAL_REPOSITORY')]").asArray().isEmpty();
        RepoGraph rolled = graphs.graph(api, GraphLevel.METHOD, "com.shop.api.CheckoutService", true, 1);
        assertThat(rolled.level()).isNotEqualTo(GraphLevel.METHOD);
        assertThat(rolled.truncated()).isTrue();
    }

    @Test
    void theReportSummarisesTheAnalysesAndFlagsAStaleOne() {
        long repo = GraphFixture.load(jdbc, writer);
        jdbc.update("INSERT INTO entry_point_annotation (annotation_fqn, label, enabled) VALUES (?, 'Test', 1)",
                GraphFixture.ENTRY_ANNOTATION);
        try {
            jdbc.update("UPDATE scm_repository SET last_indexed_commit = ? WHERE id = ?", GraphFixture.COMMIT, repo);
            assertThat(mvc.get().uri("/api/v1/repositories/" + repo + "/graph/report")).hasStatusOk().bodyJson()
                    .satisfies(json -> {
                        assertThat(json).extractingPath("$.analyzedCommit").isNull();
                        assertThat(json).extractingPath("$.stale").isEqualTo(true);
                        assertThat(json).extractingPath("$.classCount").isEqualTo(7);
                    });

            graphs.analyze(repo, GraphFixture.COMMIT);

            assertThat(mvc.get().uri("/api/v1/repositories/" + repo + "/graph/report")).hasStatusOk().bodyJson()
                    .satisfies(json -> {
                        assertThat(json).extractingPath("$.stale").isEqualTo(false);
                        assertThat(json).extractingPath("$.moduleCount").isEqualTo(2);
                        assertThat(json).extractingPath("$.packageCount").isEqualTo(4);
                        assertThat(json).extractingPath("$.criticalClasses[0].fqn").isEqualTo("com.g.a.AlphaHelper");
                        assertThat(json).extractingPath("$.criticalClasses[0].dependents").isEqualTo(5);
                        assertThat(json).extractingPath("$.cycles[0].packages").asArray()
                                .containsExactly("com.g.a", "com.g.b");
                        assertThat(json).extractingPath("$.entryPointClasses").asArray()
                                .containsExactly("com.g.web.Api");
                    });

            SettingsOverride overrides = new SettingsOverride(settings);
            overrides.set(SettingKeys.GRAPH_REPORT_TOP_N, "1");
            try {
                assertThat(mvc.get().uri("/api/v1/repositories/" + repo + "/graph/report")).hasStatusOk().bodyJson()
                        .extractingPath("$.criticalClasses.length()").isEqualTo(1);
            } finally {
                overrides.restore();
            }

            jdbc.update("UPDATE scm_repository SET last_indexed_commit = 'graph-2' WHERE id = ?", repo);
            assertThat(mvc.get().uri("/api/v1/repositories/" + repo + "/graph/report")).hasStatusOk().bodyJson()
                    .extractingPath("$.stale").isEqualTo(true);
        } finally {
            jdbc.update("DELETE FROM entry_point_annotation WHERE annotation_fqn = ?", GraphFixture.ENTRY_ANNOTATION);
        }
    }

    @Test
    void exportsGraphMlAndJsonAsAttachments() throws Exception {
        long repo = GraphFixture.load(jdbc, writer);
        String base = "/api/v1/repositories/" + repo + "/graph/export";

        var graphml = mvc.get().uri(base + "?format=graphml").exchange();
        assertThat(graphml).hasStatusOk().hasContentTypeCompatibleWith("application/xml")
                .headers().hasValue("Content-Disposition", "attachment; filename=\"repository-" + repo + "-package.graphml\"");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(graphml.getResponse().getContentAsByteArray()));
        assertThat(xml.getElementsByTagNameNS("*", "node").getLength()).isEqualTo(4);

        var json = mvc.get().uri(base + "?format=json&level=class&focus=com.g.a").exchange();
        assertThat(json).hasStatusOk().hasContentTypeCompatibleWith("application/json").headers()
                .hasValue("Content-Disposition", "attachment; filename=\"repository-" + repo + "-class.json\"");
        assertThat(json).bodyJson().extractingPath("$.nodes.length()").isEqualTo(2);
        assertThat(mvc.get().uri(base)).hasStatus(400);
        assertThat(mvc.get().uri(base + "?format=csv")).hasStatus(400);
    }

    @Test
    void theExportIsCappedByItsOwnSetting() {
        long repo = GraphFixture.load(jdbc, writer);
        Integer min = jdbc.queryForObject("SELECT min_value FROM app_setting WHERE setting_key = ?", Integer.class,
                SettingKeys.GRAPH_EXPORT_MAX_NODES);
        SettingsOverride overrides = new SettingsOverride(settings);
        try {
            jdbc.update("UPDATE app_setting SET min_value = 1 WHERE setting_key = ?", SettingKeys.GRAPH_EXPORT_MAX_NODES);
            // a no-op update drops the settings cache so the lowered bound is seen
            settings.update(SettingKeys.GRAPH_EXPORT_MAX_NODES, Integer.toString(settings.getInt(
                    SettingKeys.GRAPH_EXPORT_MAX_NODES)), "test");
            overrides.set(SettingKeys.GRAPH_EXPORT_MAX_NODES, "2");

            assertThat(mvc.get().uri("/api/v1/repositories/" + repo + "/graph/export?format=json&level=class"))
                    .hasStatusOk().bodyJson().satisfies(json -> {
                        assertThat(json).extractingPath("$.truncated").isEqualTo(true);
                        assertThat(json).extractingPath("$.level").isNotEqualTo("CLASS");
                    });
        } finally {
            overrides.restore();
            jdbc.update("UPDATE app_setting SET min_value = ? WHERE setting_key = ?", min,
                    SettingKeys.GRAPH_EXPORT_MAX_NODES);
            settings.update(SettingKeys.GRAPH_EXPORT_MAX_NODES, Integer.toString(settings.getInt(
                    SettingKeys.GRAPH_EXPORT_MAX_NODES)), "test");
        }
    }

    @Test
    void theReportOfANeverIndexedRepositoryIsEmptyAndNotStale() {
        long repo = StoreFixtures.newRepository(jdbc, "never-indexed");

        assertThat(mvc.get().uri("/api/v1/repositories/" + repo + "/graph/report")).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.stale").isEqualTo(false);
                    assertThat(json).extractingPath("$.analyzedCommit").isNull();
                    assertThat(json).extractingPath("$.classCount").isEqualTo(0);
                });
    }
}
