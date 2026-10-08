package com.graphify.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.common.exception.InvalidRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitRepositoryUrlsTest {

    private static final String BASE = "https://git.corp/scm";

    private static GitRepositoryUrls.Named one(String url) {
        return GitRepositoryUrls.check(BASE, List.of(url)).getFirst();
    }

    @Test
    void namesTheRepositoryFromTheSegmentsAfterTheBase() {
        assertThat(one(BASE + "/team/api.git")).extracting(GitRepositoryUrls.Named::projectKey,
                GitRepositoryUrls.Named::slug).containsExactly("team", "api");
        assertThat(one(BASE + "/a/b/c.git")).extracting(GitRepositoryUrls.Named::projectKey,
                GitRepositoryUrls.Named::slug).containsExactly("a/b", "c");
        assertThat(one(BASE + "/solo.git")).extracting(GitRepositoryUrls.Named::projectKey,
                GitRepositoryUrls.Named::slug).containsExactly("solo", "solo");
        assertThat(one(BASE + "/team/api")).extracting(GitRepositoryUrls.Named::projectKey,
                GitRepositoryUrls.Named::slug).containsExactly("team", "api");
    }

    @Test
    void comparesSchemeHostAndDefaultPortCaseInsensitively() {
        assertThat(one("HTTPS://GIT.CORP:443/scm/x/y").slug()).isEqualTo("y");
        assertThat(GitRepositoryUrls.name(BASE, BASE + "/x/y.git").url()).isEqualTo(BASE + "/x/y.git");
    }

    @Test
    void rejectsUrlsOffTheBase() {
        String off = "is not on the connection's base URL";
        String relative = "empty or relative path segment";
        String encoded = "encoded slash or backslash";
        String[][] cases = {
                {"https://git.corp.evil/scm/x.git", off}, {"https://git.corp/scmx/x.git", "not under the connection's base URL path"},
                {"https://git.corp/scm", "not under the connection's base URL path"},
                {"https://git.corp/scm/", "must name a repository after the base URL"},
                {"https://u:p@git.corp/scm/x.git", "must not contain credentials"},
                {BASE + "/x.git?a=1", "query or fragment"}, {BASE + "/x.git#f", "query or fragment"},
                {"https://git.corp:8443/scm/x.git", off}, {"http://git.corp/scm/x.git", off},
                {BASE + "/.git", "has no repository name"}, {BASE + "/a//b.git", relative},
                {BASE + "/a/../b.git", relative}, {BASE + "/%2e%2e/other/x.git", relative},
                {BASE + "/%2E/x.git", relative}, {BASE + "/.%2e/x.git", relative},
                {BASE + "/a%2F..%2Fb.git", encoded}, {BASE + "/a%2fb.git", encoded}, {BASE + "/a%5Cb.git", encoded},
                {BASE + "/a%5cb.git", encoded}};
        for (String[] c : cases) {
            assertThatThrownBy(() -> GitRepositoryUrls.check(BASE, List.of(c[0]))).as(c[0])
                    .isInstanceOf(InvalidRequestException.class).hasMessageContaining(c[1]);
        }
    }

    @Test
    void rejectsABaseUrlWithEncodedTraversal() {
        assertThatThrownBy(() -> GitRepositoryUrls.check("https://git.corp/scm/%2e%2e", List.of(
                "https://git.corp/scm/%2e%2e/x.git"))).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("base URL");
        assertThatThrownBy(() -> GitRepositoryUrls.check("https://git.corp/a%2Fb", List.of(
                "https://git.corp/a%2Fb/x.git"))).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("encoded slash or backslash");
    }

    @Test
    void rejectsTwoUrlsNamingTheSameRepository() {
        assertThatThrownBy(() -> GitRepositoryUrls.check(BASE, List.of(BASE + "/team/api.git", BASE + "/team/api")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("team/api");
    }

    @Test
    void rejectsTooLongNamesAndUrls() {
        assertThatThrownBy(() -> one(BASE + "/" + "x".repeat(201) + ".git")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> one(BASE + "/a/" + "x".repeat(1000))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void neverEchoesCredentials() {
        assertThatThrownBy(() -> one("https://u:p@git.corp/scm/x.git")).isInstanceOf(InvalidRequestException.class)
                .hasMessageNotContaining("p@");
    }
}
