package com.graphify.scm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScmConnectionTest {

    private static ScmConnection connection(ScmType type, String username, String secret) {
        return new ScmConnection(1, "c", type, "https://h", username, secret, List.of(), List.of());
    }

    private static String basic(String pair) {
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void githubWithoutUsernameUsesTheTokenUser() {
        assertThat(connection(ScmType.GITHUB, " ", "tok").gitAuthorization()).contains(basic("x-access-token:tok"));
        assertThat(connection(ScmType.GITHUB, null, "tok").gitAuthorization()).contains(basic("x-access-token:tok"));
    }

    @Test
    void githubWithUsernameUsesIt() {
        assertThat(connection(ScmType.GITHUB, "bob", "tok").gitAuthorization()).contains(basic("bob:tok"));
    }

    @Test
    void githubWithoutSecretSendsNothing() {
        assertThat(connection(ScmType.GITHUB, "bob", null).gitAuthorization()).isEmpty();
    }

    @Test
    void bitbucketAndGitUseBearerWithoutUsernameElseBasic() {
        for (ScmType type : List.of(ScmType.BITBUCKET_DC, ScmType.GIT)) {
            assertThat(connection(type, "", "tok").gitAuthorization()).contains("Bearer tok");
            assertThat(connection(type, "bob", "tok").gitAuthorization()).contains(basic("bob:tok"));
        }
    }
}
