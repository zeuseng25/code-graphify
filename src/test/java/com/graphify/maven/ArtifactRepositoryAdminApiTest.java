
package com.graphify.maven;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.auth.Role;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class ArtifactRepositoryAdminApiTest extends OracleIntegrationTest {

    private static final String PASSWORD = "nexus-pw-5";

    @Autowired
    ArtifactRepositories repositories;

    @TempDir
    Path dir;

    private HttpServer nexus;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM artifact_repository");
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'ARTIFACT_REPOSITORY%'");
        String expected = "Basic " + Base64.getEncoder().encodeToString(("ci:" + PASSWORD)
                .getBytes(StandardCharsets.UTF_8));
        nexus = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        nexus.createContext("/repository/maven-public/", exchange -> {
            int status = expected.equals(exchange.getRequestHeaders().getFirst("Authorization")) ? 200 : 401;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        nexus.createContext("/missing/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        nexus.start();
    }

    @AfterEach
    void tearDown() {
        nexus.stop(0);
        jdbc.update("DELETE FROM artifact_repository");
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'ARTIFACT_REPOSITORY%'");
    }

    private String url() {
        return "http://127.0.0.1:" + nexus.getAddress().getPort() + "/repository/maven-public/";
    }

    private String body(String name, String url, String password) {
        return """
                {"name":"%s","url":"%s","username":"ci",%s"mirrorOf":"*","sortOrder":0,"enabled":true}
                """.formatted(name, url, password == null ? "" : "\"secret\":\"" + password + "\",");
    }

    private long create(String name, String url, String password) throws Exception {
        MvcTestResult created = mvc.post().uri("/api/v1/admin/artifact-repositories")
                .contentType(MediaType.APPLICATION_JSON).content(body(name, url, password)).exchange();
        assertThat(created).hasStatus(201);
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    @Test
    void createsTestsAndListsARepositoryWithoutItsSecret() throws Exception {
        long id = create("nexus", url(), PASSWORD);

        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories/" + id + "/test")).hasStatusOk();
        assertThat(mvc.get().uri("/api/v1/admin/artifact-repositories")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$[0].secretSet").isEqualTo(true);
            assertThat(json).extractingPath("$[0].lastTestStatus").isEqualTo("SUCCESS");
        });
        assertThat(mvc.get().uri("/api/v1/admin/artifact-repositories/" + id)).hasStatusOk().bodyText()
                .doesNotContain(PASSWORD);
        assertThat(repositories.enabled()).extracting(ArtifactRepository::secret).containsExactly(PASSWORD);
        assertThat(jdbc.queryForList("SELECT NVL(details, '-') FROM audit_log WHERE action LIKE 'ARTIFACT_REPOSITORY%'",
                String.class)).isNotEmpty().noneMatch(details -> details.contains(PASSWORD));
    }

    @Test
    void aRejectedPasswordIsAuthFailedAndAFileRepositoryMustExist() throws Exception {
        long wrong = create("nexus", url(), "wrong-pw");
        long folder = create("local", dir.toUri().toString(), null);
        long missing = create("missing", dir.resolve("nope").toUri().toString(), null);

        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories/" + wrong + "/test")).hasStatus(502)
                .bodyText().doesNotContain("wrong-pw");
        assertThat(jdbc.queryForObject("SELECT last_test_status FROM artifact_repository WHERE id = ?", String.class,
                wrong)).isEqualTo("AUTH_FAILED");
        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories/" + folder + "/test")).hasStatusOk();
        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories/" + missing + "/test")).hasStatus(502);
    }

    @Test
    void aPathTheServerDoesNotKnowIsAFailedTest() throws Exception {
        long id = create("gone", url().replace("/repository/maven-public/", "/missing/"), PASSWORD);

        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories/" + id + "/test")).hasStatus(502).bodyText()
                .contains("path not found (HTTP 404)");
        assertThat(jdbc.queryForObject("SELECT last_test_status FROM artifact_repository WHERE id = ?", String.class,
                id)).isEqualTo("FAILED");
    }

    @Test
    void aChangedTargetNeedsTheSecretAgain() throws Exception {
        long id = create("nexus", url(), PASSWORD);

        assertThat(mvc.put().uri("/api/v1/admin/artifact-repositories/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("nexus", "https://elsewhere.example/repo/", null))).hasStatus(400);
        assertThat(mvc.put().uri("/api/v1/admin/artifact-repositories/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("nexus-2", url(), null))).hasStatusOk();
        assertThat(repositories.enabled()).extracting(ArtifactRepository::secret).containsExactly(PASSWORD);
    }

    @Test
    void validatesDeletesAndGuardsAccess() throws Exception {
        long id = create("nexus", url(), PASSWORD);

        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories").contentType(MediaType.APPLICATION_JSON)
                .content(body("nexus", url(), PASSWORD))).hasStatus(409);
        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories").contentType(MediaType.APPLICATION_JSON)
                .content(body("other", "ftp://repo", null))).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories").contentType(MediaType.APPLICATION_JSON)
                .content(body("neg", url(), null).replace("\"sortOrder\":0", "\"sortOrder\":-1"))).hasStatus(400);
        assertThat(as("viewer", Role.USER).get().uri("/api/v1/admin/artifact-repositories")).hasStatus(403);

        assertThat(mvc.delete().uri("/api/v1/admin/artifact-repositories/" + id)).hasStatus(204);
        assertThat(mvc.get().uri("/api/v1/admin/artifact-repositories/" + id)).hasStatus(404);
    }

    @Test
    void aUrlWithUserinfoIsRejectedWithoutEchoingIt() throws Exception {
        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories").contentType(MediaType.APPLICATION_JSON)
                .content(body("leaky", "https://ci:s3cr3t-pw@repo.example.com/maven/", null))).hasStatus(400)
                .bodyText().contains("must not contain credentials").doesNotContain("s3cr3t-pw")
                .doesNotContain("repo.example.com");
        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories").contentType(MediaType.APPLICATION_JSON)
                .content(body("leaky", "https://repo.example.com/maven/?token=abc", null))).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories").contentType(MediaType.APPLICATION_JSON)
                .content(body("opaque", "file:relative/path", null))).hasStatus(400);
        assertThat(repositories.enabled()).isEmpty();
    }

    @Test
    void anEmptySecretClearsTheStoredOne() throws Exception {
        long id = create("nexus", url(), PASSWORD);

        assertThat(mvc.put().uri("/api/v1/admin/artifact-repositories/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("nexus", url(), "")))
                .hasStatusOk().bodyJson().extractingPath("$.secretSet").isEqualTo(false);
        assertThat(repositories.enabled()).extracting(ArtifactRepository::secret).containsExactly((String) null);
    }

    @Test
    void unknownIdsAreNotFound() throws Exception {
        assertThat(mvc.get().uri("/api/v1/admin/artifact-repositories/987654")).hasStatus(404);
        assertThat(mvc.put().uri("/api/v1/admin/artifact-repositories/987654").contentType(MediaType.APPLICATION_JSON)
                .content(body("nexus", url(), null))).hasStatus(404);
        assertThat(mvc.delete().uri("/api/v1/admin/artifact-repositories/987654")).hasStatus(404);
        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories/987654/test")).hasStatus(404);
    }

    @Test
    void aChangedUsernameWithAStoredSecretNeedsTheSecretAgain() throws Exception {
        long id = create("nexus", url(), PASSWORD);

        assertThat(mvc.put().uri("/api/v1/admin/artifact-repositories/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("nexus", url(), null).replace("\"username\":\"ci\"", "\"username\":\"other\"")))
                .hasStatus(400);
        assertThat(repositories.enabled()).extracting(ArtifactRepository::secret).containsExactly(PASSWORD);
    }
}
