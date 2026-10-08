package com.graphify.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.scm.AuthorizationHeader;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.GitFixtures;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class GitWorkspaceTest extends OracleIntegrationTest {

    @Autowired
    GitWorkspace workspace;

    @Autowired
    AppSettings settings;

    @TempDir
    Path dir;

    private String originalWorkspace;
    private Path bare;

    @BeforeEach
    void setUp() throws Exception {
        originalWorkspace = settings.getString(SettingKeys.INDEX_WORKSPACE_DIR);
        settings.update(SettingKeys.INDEX_WORKSPACE_DIR, dir.resolve("ws").toString(), "test");
        bare = GitFixtures.bareRepository(dir, "app", Map.of("src/main/java/p/A.java", "package p; class A {}"));
    }

    @AfterEach
    void tearDown() {
        settings.update(SettingKeys.INDEX_WORKSPACE_DIR, originalWorkspace, "test");
    }

    @Test
    void readsTheRemoteHeadWithoutCloning() {
        RemoteHead head = workspace.remoteHead(GitFixtures.url(bare), Optional.empty());

        assertThat(head.branch()).isEqualTo("main");
        assertThat(head.commit()).hasSize(40);
        assertThat(Files.exists(dir.resolve("ws"))).isFalse();
    }

    @Test
    void clonesShallowThenFetchesAndResets() throws Exception {
        Path target = workspace.directoryFor(7, "SHOP", "app/../x");
        RemoteHead first = workspace.remoteHead(GitFixtures.url(bare), Optional.empty());

        assertThat(workspace.checkout(target, GitFixtures.url(bare), "main", Optional.empty())).isEqualTo(first.commit());
        assertThat(target.getFileName().toString()).doesNotContain("/").doesNotContain("..");
        assertThat(Files.exists(target.resolve(".git/shallow"))).isTrue();

        String second = GitFixtures.commit(bare, Map.of("src/main/java/p/B.java", "package p; class B {}"), "add B");
        Files.writeString(target.resolve("stray.txt"), "untracked");
        Files.writeString(target.resolve("src/main/java/p/A.java"), "locally modified");

        assertThat(workspace.checkout(target, GitFixtures.url(bare), "main", Optional.empty())).isEqualTo(second);
        assertThat(target.resolve("src/main/java/p/B.java")).exists();
        assertThat(target.resolve("stray.txt")).doesNotExist();
        assertThat(Files.readString(target.resolve("src/main/java/p/A.java"))).isEqualTo("package p; class A {}");
    }

    @Test
    void corruptWorkspaceIsRecloned() throws Exception {
        Path target = workspace.directoryFor(7, "SHOP", "app");
        workspace.checkout(target, GitFixtures.url(bare), "main", Optional.empty());
        Files.delete(target.resolve(".git/HEAD"));
        Files.writeString(target.resolve(".git/config"), "this is not git config [[[");

        String commit = workspace.checkout(target, GitFixtures.url(bare), "main", Optional.empty());

        assertThat(commit).isEqualTo(workspace.remoteHead(GitFixtures.url(bare), Optional.empty()).commit());
        assertThat(target.resolve("src/main/java/p/A.java")).exists();
    }

    @Test
    void failuresNeverExposeCredentials() {
        String url = "https://bob:hunter2@127.0.0.1:1/scm/x.git";

        assertThatThrownBy(() -> workspace.remoteHead(url, AuthorizationHeader.of("bob", "tok-secret-1")))
                .isInstanceOf(GitException.class)
                .hasMessageNotContaining("hunter2")
                .hasMessageNotContaining("tok-secret-1");
        assertThatThrownBy(() -> workspace.checkout(dir.resolve("never"), url, "main", AuthorizationHeader.of("bob", "tok-secret-1")))
                .isInstanceOf(GitException.class)
                .hasMessageNotContaining("hunter2")
                .hasMessageNotContaining("tok-secret-1");
    }

    @Test
    void dotAndBlankSegmentsStayInsideTheWorkspace() {
        Path root = dir.resolve("ws");

        Path dot = workspace.directoryFor(7, ".", "..");
        Path blank = workspace.directoryFor(7, " ", null);

        // Path.startsWith, not AssertJ's: that one resolves real paths, and these directories do not exist
        assertThat(dot.normalize().startsWith(root.resolve("7"))).isTrue();
        assertThat(dot.normalize()).isNotEqualTo(root).isNotEqualTo(root.resolve("7"));
        assertThat(dot.getFileName().toString()).doesNotContain("..");
        assertThat(blank.normalize().startsWith(root.resolve("7"))).isTrue();
        assertThat(blank.normalize()).isNotEqualTo(root.resolve("7"));
    }

    @Test
    void aRemoteDemandingCredentialsIsAnAuthenticationFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"git\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/scm/x.git";

            assertThatThrownBy(() -> workspace.remoteHead(url, AuthorizationHeader.of("bob", "tok-secret-1")))
                    .isInstanceOf(GitAuthenticationException.class)
                    .hasMessageNotContaining("tok-secret-1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void credentialsAreNotSentToAnotherHostAfterARedirect() throws Exception {
        redirectedOperation(url -> workspace.remoteHead(url, AuthorizationHeader.of("bob", "tok-secret-1")));
    }

    @Test
    void checkoutDoesNotSendCredentialsToAnotherHostAfterARedirect() throws Exception {
        redirectedOperation(url -> workspace.checkout(dir.resolve("redirected"), url, "main",
                AuthorizationHeader.of("bob", "tok-secret-1")));
    }

    /** Runs an operation against a host that redirects to another host name; the second host must see no credentials. */
    private void redirectedOperation(java.util.function.Consumer<String> operation) throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>("not called");
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/", exchange -> {
            seen.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        other.start();
        HttpServer first = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        first.createContext("/", exchange -> {
            // localhost, not 127.0.0.1: another host name
            exchange.getResponseHeaders().add("Location", "http://localhost:" + other.getAddress().getPort()
                    + "/x.git/info/refs?service=git-upload-pack");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        first.start();
        try {
            String url = "http://127.0.0.1:" + first.getAddress().getPort() + "/x.git";

            assertThatThrownBy(() -> operation.accept(url))
                    .isInstanceOf(GitException.class)
                    .hasMessageNotContaining("tok-secret-1");
            // the redirect was followed, and the second host saw no credentials
            assertThat(seen.get()).isEqualTo("null");
        } finally {
            first.stop(0);
            other.stop(0);
        }
    }

    /** A request on this thread that declared no origin, with an Authorization header JGit is told to send. */
    private static void unboundRequestWithAuthorization(String url) {
        try {
            org.eclipse.jgit.api.Git.lsRemoteRepository().setRemote(url).setTimeout(5)
                    .setTransportConfigCallback(transport -> {
                        if (transport instanceof org.eclipse.jgit.transport.TransportHttp http) {
                            http.setAdditionalHeaders(Map.of("Authorization", "Bearer tok-secret-1"));
                        }
                    }).callAsMap();
        } catch (Exception expected) {
            // the server answers 404; only the header it saw matters
        }
    }

    @Test
    void aRequestWithNoBoundOriginDropsAuthorization() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>("not called");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            unboundRequestWithAuthorization("http://127.0.0.1:" + server.getAddress().getPort() + "/x.git");

            assertThat(seen.get()).isEqualTo("null");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theBindingIsClearedAfterAFailure() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>("not called");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/x.git";
            assertThatThrownBy(() -> workspace.remoteHead(url, AuthorizationHeader.of("bob", "tok-secret-1")))
                    .isInstanceOf(GitException.class);
            assertThat(seen.get()).startsWith("Basic ");

            // same thread, same host, but nobody declared an origin any more
            unboundRequestWithAuthorization(url);

            assertThat(seen.get()).isEqualTo("null");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void namesThatCleanToTheSameTextGetDifferentDirectoriesWithTheirOwnContent() throws Exception {
        Path slash = GitFixtures.bareRepository(dir, "slash", Map.of("README.md", "from a/b"));
        Path underscore = GitFixtures.bareRepository(dir, "under", Map.of("README.md", "from a_b"));
        Path first = workspace.directoryFor(3, "a/b", "x");
        Path second = workspace.directoryFor(3, "a_b", "x");

        assertThat(first).isNotEqualTo(second);
        // readable and stable; a name safe() leaves alone keeps its plain form
        assertThat(second.getParent().getFileName().toString()).isEqualTo("a_b");
        assertThat(first.getParent().getFileName().toString()).startsWith("a_b~");
        assertThat(workspace.directoryFor(3, "a/b", "x")).isEqualTo(first);

        workspace.checkout(first, GitFixtures.url(slash), "main", Optional.empty());
        workspace.checkout(second, GitFixtures.url(underscore), "main", Optional.empty());

        assertThat(Files.readString(first.resolve("README.md"))).isEqualTo("from a/b");
        assertThat(Files.readString(second.resolve("README.md"))).isEqualTo("from a_b");
    }

    @Test
    void aWorkspaceClonedFromAnotherUrlIsClonedAgain() throws Exception {
        Path other = GitFixtures.bareRepository(dir, "other", Map.of("README.md", "other repository"));
        Path target = workspace.directoryFor(3, "SHOP", "app");
        workspace.checkout(target, GitFixtures.url(bare), "main", Optional.empty());
        assertThat(target.resolve("src/main/java/p/A.java")).exists();

        String commit = workspace.checkout(target, GitFixtures.url(other), "main", Optional.empty());

        assertThat(commit).isEqualTo(workspace.remoteHead(GitFixtures.url(other), Optional.empty()).commit());
        assertThat(Files.readString(target.resolve("README.md"))).isEqualTo("other repository");
        assertThat(target.resolve("src/main/java/p/A.java")).doesNotExist();
    }
}
