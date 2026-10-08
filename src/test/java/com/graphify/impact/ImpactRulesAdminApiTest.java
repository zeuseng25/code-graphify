package com.graphify.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.OracleIntegrationTest;
import com.graphify.auth.Role;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class ImpactRulesAdminApiTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @Autowired
    ImpactService impact;

    @TempDir
    Path work;

    private List<java.util.Map<String, Object>> savedRules;
    private List<java.util.Map<String, Object>> savedAnnotations;

    @BeforeEach
    void setUp() throws Exception {
        ShopFixture.load(jdbc, writer, work);
        savedRules = jdbc.queryForList("SELECT usage_kind, propagates, shown_at_level1 FROM impact_relation_rule");
        savedAnnotations = jdbc.queryForList("SELECT annotation_fqn, label, enabled FROM entry_point_annotation");
    }

    @AfterEach
    void restore() {
        savedRules.forEach(row -> jdbc.update("UPDATE impact_relation_rule SET propagates = ?, shown_at_level1 = ? "
                + "WHERE usage_kind = ?", row.get("PROPAGATES"), row.get("SHOWN_AT_LEVEL1"), row.get("USAGE_KIND")));
        jdbc.update("DELETE FROM entry_point_annotation");
        savedAnnotations.forEach(row -> jdbc.update("INSERT INTO entry_point_annotation (annotation_fqn, label, enabled) "
                + "VALUES (?, ?, ?)", row.get("ANNOTATION_FQN"), row.get("LABEL"), row.get("ENABLED")));
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'ENTRY_POINT_ANNOTATION%' OR action = 'IMPACT_RULE_UPDATED'");
    }

    private ImpactResult analyzeFormat() {
        return impact.analyze(new ImpactRequest(
                List.of(ShopFixture.symbolId(jdbc, "com.shop.lib.PriceFormatter#format(int)")), null, 3, null, null));
    }

    @Test
    void changesApplyToTheNextAnalysis() {
        assertThat(analyzeFormat().entryPoints()).extracting(EntryPoint::key, EntryPoint::label)
                .contains(tuple("com.shop.api.OrderController#checkout()", "HTTP"));
        assertThat(analyzeFormat().nodes()).extracting(ImpactNode::level).contains(3);

        long postMapping = jdbc.queryForObject("SELECT id FROM entry_point_annotation WHERE annotation_fqn = "
                + "'org.springframework.web.bind.annotation.PostMapping'", Long.class);
        assertThat(mvc.put().uri("/api/v1/admin/entry-point-annotations/" + postMapping)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"annotationFqn":"org.springframework.web.bind.annotation.PostMapping","label":"HTTP POST",
                         "enabled":false}
                        """)).hasStatusOk();
        assertThat(mvc.put().uri("/api/v1/admin/impact-rules/CALL").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propagates\":false,\"shownAtLevel1\":true}")).hasStatusOk().bodyJson()
                .extractingPath("$.propagates").isEqualTo(false);

        ImpactResult after = analyzeFormat();
        assertThat(after.entryPoints()).extracting(EntryPoint::key)
                .doesNotContain("com.shop.api.OrderController#checkout()");
        assertThat(after.nodes()).extracting(ImpactNode::level).doesNotContain(2, 3);
        assertThat(mvc.get().uri("/api/v1/admin/impact-rules")).hasStatusOk().bodyJson()
                .extractingPath("$[?(@.kind == 'CALL')].propagates").asArray().containsExactly(false);
    }

    @Test
    void managesEntryPointAnnotations() {
        assertThat(mvc.post().uri("/api/v1/admin/entry-point-annotations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"annotationFqn\":\"com.corp.Job\",\"label\":\"Corp job\",\"enabled\":true}"))
                .hasStatus(201);
        assertThat(mvc.post().uri("/api/v1/admin/entry-point-annotations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"annotationFqn\":\"com.corp.Job\",\"label\":\"again\",\"enabled\":true}")).hasStatus(409);
        assertThat(mvc.post().uri("/api/v1/admin/entry-point-annotations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"annotationFqn\":\"not a name!\",\"label\":\"x\",\"enabled\":true}")).hasStatus(400);
        long id = jdbc.queryForObject("SELECT id FROM entry_point_annotation WHERE annotation_fqn = 'com.corp.Job'",
                Long.class);

        assertThat(mvc.delete().uri("/api/v1/admin/entry-point-annotations/" + id)).hasStatus(204);
        assertThat(mvc.delete().uri("/api/v1/admin/entry-point-annotations/" + id)).hasStatus(404);
        assertThat(mvc.put().uri("/api/v1/admin/impact-rules/NOPE").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propagates\":true,\"shownAtLevel1\":true}")).hasStatus(400);
        assertThat(mvc.put().uri("/api/v1/admin/impact-rules/CALL").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propagates\":true}")).hasStatus(400);
        assertThat(as("viewer", Role.USER).get().uri("/api/v1/admin/impact-rules")).hasStatus(403);
    }
}
