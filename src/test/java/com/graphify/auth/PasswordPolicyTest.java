package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PasswordPolicyTest extends OracleIntegrationTest {

    @Autowired
    PasswordPolicy policy;

    @Test
    void enforcesTheConfiguredMinimumLength() {
        assertThatThrownBy(() -> policy.check("short")).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("12");
        assertThatThrownBy(() -> policy.check(null)).isInstanceOf(InvalidRequestException.class);
        assertThatCode(() -> policy.check("long-enough-password")).doesNotThrowAnyException();
    }

    @Test
    void rejectsPasswordsLongerThanBcryptReads() {
        assertThatCode(() -> policy.check("a".repeat(72))).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.check("a".repeat(73))).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("72 bytes");
        // 37 two-byte characters are 74 UTF-8 bytes
        assertThatThrownBy(() -> policy.check("ş".repeat(37))).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("72 bytes");
    }
}
