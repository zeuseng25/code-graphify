package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthControllerRecordsTest {

    @Test
    void requestBodiesNeverPrintTheirPasswords() {
        assertThat(new AuthController.Credentials("ayse", "login-secret").toString()).contains("ayse")
                .doesNotContain("login-secret");
        assertThat(new AuthController.PasswordChange("current-secret", "new-secret").toString())
                .doesNotContain("current-secret").doesNotContain("new-secret");
    }
}
