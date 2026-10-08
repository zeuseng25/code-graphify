package com.graphify.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.OracleIntegrationTest;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.FakeGitHub;
import com.graphify.testsupport.SettingsOverride;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;

@ExtendWith(OutputCaptureExtension.class)
class GitHubClientTest extends OracleIntegrationTest {

    @Autowired
    GitHubClient client;

    @Autowired
    AppSettings settings;

    private SettingsOverride overrides;
    private FakeGitHub github;

    @BeforeEach
    void setUp() throws Exception {
        overrides = new SettingsOverride(settings);
        overrides.set(SettingKeys.SCM_PAGE_SIZE, "2");
        overrides.set(SettingKeys.SCM_RETRY_BACKOFF, "PT0.01S");
        github = new FakeGitHub().start();
    }

    @AfterEach
    void tearDown() {
        github.close();
        overrides.restore();
    }

    private ScmConnection connection(String username, String secret, List<String> orgs, List<String> exclude) {
        return new ScmConnection(1, "hub", ScmType.GITHUB, github.baseUrl(), username, secret, orgs, exclude);
    }

    @Test
    void listsEveryPageOfEachOrganization() {
        for (String name : List.of("a", "b", "c", "d", "e")) {
            github.addRepository("shop", name, "https://github.test/shop/" + name + ".git");
        }
        github.addRepository("pay", "ledger", "https://github.test/pay/ledger.git");

        List<RemoteRepository> repositories = client.listRepositories(
                connection(null, "tok", List.of("shop", "pay"), List.of()));

        assertThat(repositories).hasSize(6).extracting(RemoteRepository::projectKey, RemoteRepository::slug,
                RemoteRepository::cloneUrl).contains(tuple("shop", "a", "https://github.test/shop/a.git"),
                tuple("pay", "ledger", "https://github.test/pay/ledger.git"));
        assertThat(github.requestedOrgs()).containsExactly("shop", "shop", "shop", "pay");
        assertThat(github.requestedPages()).containsExactly("1", "2", "3", "1");
        assertThat(github.requestedPerPage()).containsOnly("2");
    }

    @Test
    void sendsTheTokenAsBearerWithTheGitHubHeaders() {
        github.addRepository("shop", "api", "https://github.test/shop/api.git");

        client.listRepositories(connection("bob", "tok", List.of("shop"), List.of()));

        assertThat(github.authorizations()).containsOnly("Bearer tok");
        assertThat(github.acceptHeaders()).containsOnly("application/vnd.github+json");
        assertThat(github.apiVersions()).allMatch(v -> v != null && !v.isBlank());
    }

    @Test
    void skipsArchivedAndAppliesExcludes() {
        github.addRepository("shop", "api", "https://github.test/shop/api.git")
                .addRepository("shop", "legacy-a", "https://github.test/shop/legacy-a.git")
                .addArchived("shop", "old", "https://github.test/shop/old.git");

        List<RemoteRepository> repositories = client.listRepositories(
                connection(null, "tok", List.of("shop"), List.of("shop/legacy-*")));

        assertThat(repositories).extracting(RemoteRepository::slug).containsExactly("api");
    }

    @Test
    void aRejectedTokenIsAnAuthenticationFailure() {
        github.addRepository("shop", "api", "https://github.test/shop/api.git").requireAuthorization("Bearer other");

        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok-secret", List.of("shop"), List.of())))
                .isInstanceOf(ScmAuthenticationException.class)
                .message().doesNotContain("tok-secret");
    }

    @Test
    void retriesServerErrors() {
        github.addRepository("shop", "api", "https://github.test/shop/api.git").failNext(502, 2);
        overrides.set(SettingKeys.SCM_RETRY_COUNT, "2");

        assertThat(client.listRepositories(connection(null, "tok", List.of("shop"), List.of()))).hasSize(1);

        github.failNext(502, 5);
        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of("shop"), List.of())))
                .isInstanceOf(ScmException.class).isNotInstanceOf(ScmAuthenticationException.class);
    }

    @Test
    void anUnknownOrganizationFailsTheListing() {
        github.addRepository("shop", "api", "https://github.test/shop/api.git");

        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of("ghost"), List.of())))
                .isInstanceOf(ScmException.class).hasMessageContaining("ghost")
                .hasMessageContaining("a personal account is not an organization, use includeOwnRepositories");
    }

    @Test
    void refusesANextLinkOnAnotherHost() throws Exception {
        for (String name : List.of("a", "b", "c")) {
            github.addRepository("shop", name, "https://github.test/shop/" + name + ".git");
        }
        try (FakeGitHub other = new FakeGitHub().start().addRepository("shop", "x", "https://github.test/shop/x.git")) {
            github.nextLinkOverride(other.baseUrl() + "/orgs/shop/repos?per_page=2&page=2");

            assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of("shop"), List.of())))
                    .isInstanceOf(ScmException.class).hasMessageContaining("outside the base URL");
            assertThat(other.requestedPages()).isEmpty();
            assertThat(other.authorizations()).isEmpty();
            assertThat(github.requestedPages()).hasSize(1);
        }
    }

    @Test
    void doesNotFollowARedirectToAnotherHost() throws Exception {
        try (FakeGitHub other = new FakeGitHub().start().addRepository("shop", "x", "https://github.test/shop/x.git")) {
            github.addRepository("shop", "a", "https://github.test/shop/a.git")
                    .redirectTo(other.baseUrl() + "/orgs/shop/repos?per_page=2&page=1");

            assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of("shop"), List.of())))
                    .isInstanceOf(ScmException.class).hasMessageContaining("HTTP 301");
            assertThat(other.requestedPages()).isEmpty();
        }
    }

    @Test
    void aRateLimitedForbiddenIsNotAnAuthenticationFailure() {
        github.addRepository("shop", "a", "https://github.test/shop/a.git").failNext(403, 5)
                .failureHeader("X-RateLimit-Remaining", "0");

        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of("shop"), List.of())))
                .isInstanceOf(ScmException.class).isNotInstanceOf(ScmAuthenticationException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    void aTooManyRequestsWithRetryAfterIsARateLimit() {
        github.addRepository("shop", "a", "https://github.test/shop/a.git").failNext(429, 5)
                .failureHeader("Retry-After", "60");

        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of("shop"), List.of())))
                .isInstanceOf(ScmException.class).isNotInstanceOf(ScmAuthenticationException.class)
                .hasMessageContaining("rate limit").hasMessageContaining("60");
    }

    @Test
    void aPlainForbiddenStaysAnAuthenticationFailure() {
        github.addRepository("shop", "a", "https://github.test/shop/a.git").failNext(403, 5);

        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of("shop"), List.of())))
                .isInstanceOf(ScmAuthenticationException.class);
    }

    @Test
    void skipsDisabledRepositoriesAndUnacceptableCloneUrls() {
        github.addRepository("shop", "ok", "http://github.test/shop/ok.git")
                .addDisabled("shop", "off", "https://github.test/shop/off.git")
                .addRepository("shop", "creds", "https://u:p@github.test/shop/creds.git")
                .addRepository("shop", "ssh", "ssh://git@github.test/shop/ssh.git")
                .addRepository("shop", "nohost", "https:///shop/nohost.git");

        assertThat(client.listRepositories(connection(null, "tok", List.of("shop"), List.of())))
                .extracting(RemoteRepository::slug).containsExactly("ok");
    }

    @Test
    void theWarningForASkippedCloneUrlCarriesNoCredentials(CapturedOutput output) {
        github.addRepository("shop", "creds", "https://leaky-user:leaky-pass@github.test/shop/creds.git");

        client.listRepositories(connection(null, "tok", List.of("shop"), List.of()));

        assertThat(output.getAll()).contains("Skipping GitHub repository shop/creds")
                .doesNotContain("leaky-user").doesNotContain("leaky-pass");
    }

    @Test
    void anEmptyOrganizationListFails() {
        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of(), List.of())))
                .isInstanceOf(ScmException.class);
        assertThat(github.requestedPages()).isEmpty();
    }

    @Test
    void refusesANextLinkWithCredentialsInTheUrl() {
        for (String name : List.of("a", "b", "c")) {
            github.addRepository("shop", name, "https://github.test/shop/" + name + ".git");
        }
        github.nextLinkOverride(github.baseUrl().replace("http://", "http://evil:pw@") + "/orgs/shop/repos?page=2");

        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of("shop"), List.of())))
                .isInstanceOf(ScmException.class).message().doesNotContain("pw@");
        assertThat(github.requestedPages()).hasSize(1);
    }

    @Test
    void refusesALinkLoop() {
        for (String name : List.of("a", "b", "c")) {
            github.addRepository("shop", name, "https://github.test/shop/" + name + ".git");
        }
        github.nextLinkOverride(github.baseUrl() + "/orgs/shop/repos?per_page=2&page=1");

        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of("shop"), List.of())))
                .isInstanceOf(ScmException.class).hasMessageContaining("already fetched");
        assertThat(github.requestedPages()).hasSize(1);
    }

    @Test
    void testSendsOneRequestWithoutRetry() {
        github.addRepository("shop", "api", "https://github.test/shop/api.git").failNext(502, 1);

        assertThatThrownBy(() -> client.test(connection(null, "tok", List.of("shop", "pay"), List.of())))
                .isInstanceOf(ScmException.class);
        assertThat(github.requestedPages()).hasSize(1);
        assertThat(github.requestedPerPage()).containsExactly("1");
        assertThat(github.requestedOrgs()).containsExactly("shop");
    }

    private ScmConnection own(List<String> orgs, List<String> exclude) {
        return new ScmConnection(1, "hub", ScmType.GITHUB, github.baseUrl(), null, "tok", orgs, exclude, List.of(),
                true);
    }

    @Test
    void listsEveryPageOfTheTokenOwnersRepositories() {
        github.ownerLogin("zeuseng25");
        for (String name : List.of("a", "b", "c")) {
            github.addOwnRepository(name, "https://github.test/zeuseng25/" + name + ".git");
        }
        github.addOwnArchived("old", "https://github.test/zeuseng25/old.git");

        List<RemoteRepository> repositories = client.listRepositories(own(List.of(), List.of()));

        assertThat(repositories).extracting(RemoteRepository::projectKey, RemoteRepository::slug)
                .containsExactly(tuple("zeuseng25", "a"), tuple("zeuseng25", "b"), tuple("zeuseng25", "c"));
        assertThat(github.requestedPaths()).containsOnly("/user/repos");
        assertThat(github.requestedQueries()).allMatch(q -> q.contains("affiliation=owner")
                && q.contains("visibility=all") && q.contains("per_page=2"));
        assertThat(github.authorizations()).containsOnly("Bearer tok");
    }

    @Test
    void listsOwnRepositoriesNextToTheOrganizations() {
        github.ownerLogin("zeuseng25").addOwnRepository("zeus-fw", "https://github.test/zeuseng25/zeus-fw.git")
                .addOwnRepository("backend-old", "https://github.test/zeuseng25/backend-old.git")
                .addRepository("shop", "api", "https://github.test/shop/api.git");

        List<RemoteRepository> repositories = client.listRepositories(
                own(List.of("shop"), List.of("zeuseng25/*-old")));

        assertThat(repositories).extracting(RemoteRepository::projectKey, RemoteRepository::slug)
                .containsExactly(tuple("shop", "api"), tuple("zeuseng25", "zeus-fw"));
    }

    @Test
    void aRepositoryListedTwiceIsKeptOnce() {
        // the fake answers the same owner/name (differing only in case) from both listings; the first one is kept
        github.ownerLogin("Shop").addOwnRepository("api", "https://github.test/shop/api.git")
                .addRepository("shop", "api", "https://github.test/shop/api.git");

        assertThat(client.listRepositories(own(List.of("shop"), List.of())))
                .extracting(RemoteRepository::projectKey, RemoteRepository::slug).containsExactly(tuple("shop", "api"));
    }

    @Test
    void refusesAnOwnRepositoryNextLinkOnAnotherHost() throws Exception {
        try (FakeGitHub other = new FakeGitHub().start()) {
            github.addOwnRepository("a", "https://github.test/o/a.git").addOwnRepository("b", "https://github.test/o/b.git")
                    .addOwnRepository("c", "https://github.test/o/c.git")
                    .nextLinkOverride(other.baseUrl() + "/user/repos?affiliation=owner&visibility=all&page=2");

            assertThatThrownBy(() -> client.listRepositories(own(List.of(), List.of())))
                    .isInstanceOf(ScmException.class).hasMessageContaining("outside the base URL");
            assertThat(other.requestedPages()).isEmpty();
            assertThat(other.authorizations()).isEmpty();
        }
    }

    @Test
    void testsAnOwnOnlyConnectionWithOneUserRequest() {
        client.test(own(List.of(), List.of()));

        assertThat(github.requestedPaths()).containsExactly("/user");
    }

    @Test
    void anOwnOnlyTestWithARejectedTokenIsAnAuthenticationFailure() {
        github.requireAuthorization("Bearer other");

        assertThatThrownBy(() -> client.test(new ScmConnection(1, "hub", ScmType.GITHUB, github.baseUrl(), null,
                "tok-secret", List.of(), List.of(), List.of(), true)))
                .isInstanceOf(ScmAuthenticationException.class).message().doesNotContain("tok-secret");
        assertThat(github.requestedPaths()).containsExactly("/user");
    }

    @Test
    void anOwnOnlyTestIsNotRetried() {
        overrides.set(SettingKeys.SCM_RETRY_COUNT, "3");
        github.failNext(502, 1);

        assertThatThrownBy(() -> client.test(own(List.of(), List.of()))).isInstanceOf(ScmException.class);
        assertThat(github.requestedPaths()).containsExactly("/user");
    }

    @Test
    void anOwnRepositoriesRateLimitIsNotAnAuthenticationFailure() {
        github.failNext(403, 1).failureHeader("X-RateLimit-Remaining", "0");

        assertThatThrownBy(() -> client.listRepositories(own(List.of(), List.of())))
                .isInstanceOf(ScmException.class).isNotInstanceOf(ScmAuthenticationException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    void neitherOrganizationsNorOwnRepositoriesFails() {
        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of(), List.of())))
                .isInstanceOf(ScmException.class);
        assertThatThrownBy(() -> client.test(connection(null, "tok", List.of(), List.of())))
                .isInstanceOf(ScmException.class);
        assertThat(github.requestedPaths()).isEmpty();
    }
}
