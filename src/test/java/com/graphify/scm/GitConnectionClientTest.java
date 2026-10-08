package com.graphify.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.StoreFixtures;
import com.graphify.testsupport.GitFixtures;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class GitConnectionClientTest extends OracleIntegrationTest {

    @Autowired
    GitConnectionClient client;

    @Autowired
    RepositorySync sync;

    @TempDir
    Path dir;

    @BeforeEach
    void allowLocalFileBases() {
        // the fixtures are local repositories; production code only accepts http(s) bases
        client.allowedSchemes = Set.of("http", "https", "file");
    }

    @AfterEach
    void restoreSchemes() {
        client.allowedSchemes = Set.of("http", "https");
    }

    private ScmConnection connection(long id, String baseUrl, String username, String secret, List<String> urls) {
        return new ScmConnection(id, "plain", ScmType.GIT, baseUrl, username, secret, List.of(), List.of(), urls);
    }

    private ScmConnection twoRepositories(long id) throws Exception {
        Path api = GitFixtures.bareRepository(dir, "api", Map.of("README.md", "api"));
        Path lib = GitFixtures.bareRepository(dir, "lib", Map.of("README.md", "lib"));
        return connection(id, dir.toUri().toString(), null, null, List.of(GitFixtures.url(api), GitFixtures.url(lib)));
    }

    @Test
    void namesComeFromTheUrls() throws Exception {
        List<RemoteRepository> listed = client.listRepositories(twoRepositories(1));

        assertThat(listed).extracting(RemoteRepository::projectKey).containsExactly("api", "lib");
        assertThat(listed).extracting(RemoteRepository::slug).containsExactly("api", "lib");
        assertThat(listed).extracting(RemoteRepository::cloneUrl).allMatch(url -> url.startsWith("file:"));
    }

    @Test
    void aStoredUrlThatNoLongerFitsTheBaseFailsTheWholeListing() throws Exception {
        ScmConnection good = twoRepositories(1);
        ScmConnection moved = connection(1, "file:///elsewhere/", null, null, good.repositoryUrls());

        assertThatThrownBy(() -> client.listRepositories(moved)).isInstanceOf(ScmException.class)
                .hasMessageContaining("no longer valid");
    }

    @Test
    void anEmptyUrlListIsAnErrorNotAnEmptyListing() {
        ScmConnection empty = connection(1, dir.toUri().toString(), null, null, List.of());

        assertThatThrownBy(() -> client.listRepositories(empty)).isInstanceOf(ScmException.class)
                .hasMessageContaining("lists no repository URL");
        assertThatThrownBy(() -> client.test(empty)).isInstanceOf(ScmException.class);
    }

    @Test
    void testReadsTheFirstRepositoryHead() throws Exception {
        client.test(twoRepositories(1));
    }

    @Test
    void aServerDemandingCredentialsIsAnAuthenticationFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"git\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/scm/";
            ScmConnection rejected = connection(1, base, "bob", "tok-secret-1", List.of(base + "x.git"));

            assertThatThrownBy(() -> client.test(rejected)).isInstanceOf(ScmAuthenticationException.class)
                    .hasMessageNotContaining("tok-secret-1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anUnreachableServerIsAnScmException() {
        String base = "http://127.0.0.1:1/scm/";
        ScmConnection down = connection(1, base, "bob", "tok-secret-1", List.of(base + "x.git"));

        assertThatThrownBy(() -> client.test(down)).isInstanceOf(ScmException.class)
                .isNotInstanceOf(ScmAuthenticationException.class)
                .hasMessageNotContaining("tok-secret-1");
    }

    @Test
    void aBaseUrlThatIsNotHttpIsRejectedByDefault() throws Exception {
        client.allowedSchemes = Set.of("http", "https");
        ScmConnection local = twoRepositories(1);

        assertThatThrownBy(() -> client.listRepositories(local)).isInstanceOf(ScmException.class)
                .hasMessageContaining("not http or https");
        assertThatThrownBy(() -> client.test(local)).isInstanceOf(ScmException.class)
                .hasMessageContaining("not http or https");
        ScmConnection ssh = connection(1, "ssh://git.corp/scm", null, null, List.of("ssh://git.corp/scm/x.git"));
        assertThatThrownBy(() -> client.test(ssh)).isInstanceOf(ScmException.class)
                .hasMessageContaining("not http or https");
    }

    @Test
    void syncInsertsTheListedRepositories() throws Exception {
        StoreFixtures.cleanIndexTables(jdbc);
        String base = dir.toUri().toString();
        jdbc.update("INSERT INTO scm_connection (name, type, base_url) VALUES ('plain', 'GIT', ?)", base);
        long id = jdbc.queryForObject("SELECT id FROM scm_connection WHERE name = 'plain'", Long.class);

        assertThat(sync.sync(twoRepositories(id))).isEqualTo(new RepositorySync.SyncResult(2, 2, 0, 0));

        assertThat(jdbc.queryForList("SELECT project_key || '/' || slug FROM scm_repository ORDER BY slug",
                String.class)).containsExactly("api/api", "lib/lib");
    }
}
