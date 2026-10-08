package com.graphify.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.FakeBitbucket;
import com.graphify.testsupport.SettingsOverride;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BitbucketDataCenterClientTest extends OracleIntegrationTest {

    @Autowired
    BitbucketDataCenterClient client;

    @Autowired
    AppSettings settings;

    private SettingsOverride overrides;
    private FakeBitbucket bitbucket;

    @BeforeEach
    void setUp() throws Exception {
        overrides = new SettingsOverride(settings);
        overrides.set(SettingKeys.SCM_PAGE_SIZE, "2");
        overrides.set(SettingKeys.SCM_RETRY_BACKOFF, "PT0.01S");
        bitbucket = new FakeBitbucket().start();
    }

    @AfterEach
    void tearDown() {
        bitbucket.close();
        overrides.restore();
    }

    private ScmConnection connection(String username, String secret, List<String> include, List<String> exclude) {
        return new ScmConnection(1, "corp", ScmType.BITBUCKET_DC, bitbucket.baseUrl(), username, secret, include, exclude);
    }

    @Test
    void readsEveryPageSkipsArchivedAndAppliesFilters() {
        bitbucket.addRepository("SHOP", "api", "https://scm/scm/shop/api.git")
                .addRepository("SHOP", "lib", "https://scm/scm/shop/lib.git")
                .addArchived("SHOP", "old", "https://scm/scm/shop/old.git")
                .addRepository("SHOP", "tmp-archive", "https://scm/scm/shop/tmp-archive.git")
                .addRepository("HR", "portal", "https://scm/scm/hr/portal.git");

        List<RemoteRepository> repositories = client.listRepositories(
                connection(null, "tok", List.of("SHOP"), List.of("SHOP/*-archive")));

        assertThat(repositories).extracting(RemoteRepository::projectKey, RemoteRepository::slug, RemoteRepository::cloneUrl)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("SHOP", "api", "https://scm/scm/shop/api.git"),
                        org.assertj.core.api.Assertions.tuple("SHOP", "lib", "https://scm/scm/shop/lib.git"));
        assertThat(bitbucket.requestedStarts()).containsExactly("0", "2", "4");
        assertThat(bitbucket.authorizations()).containsOnly("Bearer tok");
    }

    @Test
    void retriesServerErrorsWithBackoff() {
        bitbucket.addRepository("SHOP", "api", "https://scm/scm/shop/api.git").failNext(503, 2);

        assertThat(client.listRepositories(connection(null, "tok", List.of(), List.of()))).hasSize(1);
        assertThat(bitbucket.requestedStarts()).hasSize(3);
    }

    @Test
    void givesUpAfterTheConfiguredRetries() {
        bitbucket.failNext(500, 10);

        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of(), List.of())))
                .isInstanceOf(ScmException.class)
                .isNotInstanceOf(ScmAuthenticationException.class);
        assertThat(bitbucket.requestedStarts()).hasSize(1 + settings.getInt(SettingKeys.SCM_RETRY_COUNT));
    }

    @Test
    void rejectedCredentialsAreNotRetriedAndNotLeaked() {
        bitbucket.requireAuthorization("Bearer right");

        assertThatThrownBy(() -> client.listRepositories(connection(null, "wrong-token-xyz", List.of(), List.of())))
                .isInstanceOf(ScmAuthenticationException.class)
                .hasMessageNotContaining("wrong-token-xyz");
        assertThat(bitbucket.requestedStarts()).hasSize(1);
    }

    @Test
    void aPageWithoutValuesFailsInsteadOfListingNothing() {
        bitbucket.addRepository("SHOP", "api", "https://scm/scm/shop/api.git").respondRaw(200, "{}");

        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of(), List.of())))
                .isInstanceOf(ScmException.class)
                .hasMessageContaining("corp");
        assertThat(bitbucket.requestedStarts()).hasSize(1);
    }

    @Test
    void aRedirectWithoutBodyFails() {
        bitbucket.respondRaw(302, "");

        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of(), List.of())))
                .isInstanceOf(ScmException.class)
                .hasMessageContaining("corp");
    }

    @Test
    void anUnreadableBodyFailsWithAMaskedMessage() {
        bitbucket.respondRaw(200, "{\"values\": \"https://bob:hunter2@scm/x\"");

        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of(), List.of())))
                .isInstanceOf(ScmException.class)
                .hasMessageNotContaining("hunter2");
        assertThat(bitbucket.requestedStarts()).hasSize(1);
    }

    @Test
    void aNextPageStartThatDoesNotAdvanceFailsInsteadOfLooping() {
        bitbucket.respondRaw(200, "{\"isLastPage\":false,\"nextPageStart\":0,\"values\":[]}");

        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of(), List.of())))
                .isInstanceOf(ScmException.class)
                .hasMessageContaining("nextPageStart");
        assertThat(bitbucket.requestedStarts()).hasSize(1);
    }

    @Test
    void aMissingNextPageStartOnANonLastPageFails() {
        bitbucket.respondRaw(200, "{\"isLastPage\":false,\"values\":[]}");

        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of(), List.of())))
                .isInstanceOf(ScmException.class);
    }
}
