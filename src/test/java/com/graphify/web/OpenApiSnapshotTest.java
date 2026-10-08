package com.graphify.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The frontend's API types are generated from frontend/openapi.json; this keeps that snapshot equal to the live
 * document. To refresh it: ./mvnw test -Dtest=OpenApiSnapshotTest -Dopenapi.update=true, then npm run generate:api.
 */
class OpenApiSnapshotTest extends OracleIntegrationTest {

    /** The snapshot, relative to the Maven project directory the tests run in. */
    private static final Path SNAPSHOT = Path.of("frontend", "openapi.json");

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void theCommittedSnapshotMatchesTheLiveDocument() throws Exception {
        String live = mvc.get().uri("/api/v1/openapi.json").exchange().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        ObjectNode document = (ObjectNode) json.readTree(live);
        document.remove("servers"); // host-specific
        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, json.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n");
        }
        assertThat(Files.exists(SNAPSHOT)).as("frontend/openapi.json exists").isTrue();
        JsonNode committed = json.readTree(Files.readString(SNAPSHOT));
        assertThat(committed).as("frontend/openapi.json is stale: run ./mvnw test -Dtest=OpenApiSnapshotTest "
                + "-Dopenapi.update=true, then npm run generate:api in frontend/").isEqualTo(document);
    }
}
