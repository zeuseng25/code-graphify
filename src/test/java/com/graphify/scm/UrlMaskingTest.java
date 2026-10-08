package com.graphify.scm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class UrlMaskingTest {

    @Test
    void masksUserInfoInEveryUrl() {
        assertThat(UrlMasking.mask("clone https://bob:s3cr3t@scm.corp/scm/a.git failed; retry http://t0k@h/x"))
                .isEqualTo("clone https://***@scm.corp/scm/a.git failed; retry http://***@h/x")
                .doesNotContain("s3cr3t").doesNotContain("t0k");
        assertThat(UrlMasking.mask("no url here")).isEqualTo("no url here");
        assertThat(UrlMasking.mask(null)).isNull();
    }

    @Test
    void buildsBearerOrBasicAuthorization() {
        assertThat(AuthorizationHeader.of(null, "tok")).contains("Bearer tok");
        assertThat(AuthorizationHeader.of(" ", "tok")).contains("Bearer tok");
        assertThat(AuthorizationHeader.of("bob", "pw")).contains("Basic Ym9iOnB3");
        assertThat(AuthorizationHeader.of("bob", null)).isEqualTo(Optional.empty());
    }
}
