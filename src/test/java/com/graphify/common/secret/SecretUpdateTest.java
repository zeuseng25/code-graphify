package com.graphify.common.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.common.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

class SecretUpdateTest {

    private static String resolve(String submitted, String stored, boolean sameTarget) {
        return SecretUpdate.resolve(submitted, stored, sameTarget, 10, "token", "the URL");
    }

    @Test
    void nullKeepsTheStoredSecretOnlyForTheSameTarget() {
        assertThat(resolve(null, "s3cret", true)).isEqualTo("s3cret");
        assertThat(resolve(null, null, false)).isNull();
        assertThatThrownBy(() -> resolve(null, "s3cret", false)).isInstanceOf(InvalidRequestException.class)
                .hasMessage("Re-enter the token when changing the URL").hasMessageNotContaining("s3cret");
    }

    @Test
    void emptyClearsAndANewValueReplacesWithinTheLimit() {
        assertThat(resolve("", "s3cret", false)).isNull();
        assertThat(resolve("fresh", "s3cret", false)).isEqualTo("fresh");
        assertThatThrownBy(() -> resolve("x".repeat(11), null, true)).isInstanceOf(InvalidRequestException.class)
                .hasMessage("The token is longer than 10 bytes");
    }
}
