package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.testsupport.AuthFixtures;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LocalAccountsTest extends OracleIntegrationTest {

    @Autowired
    AppUsers users;

    @Autowired
    LocalAccounts accounts;

    @BeforeEach
    void setUp() {
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
        AuthFixtures.createLocal(users, accounts, "$2a$10$hash", "ops", Role.ADMIN, true);
    }

    @AfterEach
    void clean() {
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
    }

    @Test
    void failuresLockTheAccountAtTheLimitAndSuccessResets() {
        assertThat(accounts.recordFailure("ops", 3, Duration.ofMinutes(15))).isFalse();
        accounts.recordSuccess("ops");
        assertThat(accounts.find("ops").orElseThrow().failedAttempts()).isZero();

        assertThat(accounts.recordFailure("ops", 2, Duration.ofMinutes(15))).isFalse();
        assertThat(accounts.recordFailure("ops", 2, Duration.ofMinutes(15))).isTrue();

        LocalAccount locked = accounts.find("ops").orElseThrow();
        assertThat(locked.locked()).isTrue();
        assertThat(locked.lockedUntil()).isNotNull();

        accounts.recordSuccess("ops");
        assertThat(accounts.find("ops").orElseThrow().locked()).isFalse();
    }

    @Test
    void anExpiredLockIsNoLongerLocked() {
        accounts.recordFailure("ops", 1, Duration.ofMillis(1));
        jdbc.update("UPDATE local_account SET locked_until = SYSTIMESTAMP - INTERVAL '1' SECOND WHERE username = 'ops'");

        assertThat(accounts.find("ops").orElseThrow().locked()).isFalse();
    }

    @Test
    void failuresCountAgainFromOneAfterALockExpires() {
        accounts.recordFailure("ops", 2, Duration.ofMinutes(15));
        assertThat(accounts.recordFailure("ops", 2, Duration.ofMinutes(15))).isTrue();
        jdbc.update("UPDATE local_account SET locked_until = SYSTIMESTAMP - INTERVAL '1' SECOND WHERE username = 'ops'");

        assertThat(accounts.recordFailure("ops", 2, Duration.ofMinutes(15))).isFalse();
        LocalAccount restarted = accounts.find("ops").orElseThrow();
        assertThat(restarted.failedAttempts()).isEqualTo(1);
        assertThat(restarted.lockedUntil()).isNull();

        assertThat(accounts.recordFailure("ops", 2, Duration.ofMinutes(15))).isTrue();
    }

    @Test
    void aSuccessDoesNotClearALockThatWasSetMeanwhile() {
        accounts.recordFailure("ops", 1, Duration.ofMinutes(15));

        assertThat(accounts.recordSuccessUnlessLocked("ops")).isFalse();
        assertThat(accounts.find("ops").orElseThrow().locked()).isTrue();

        jdbc.update("UPDATE local_account SET locked_until = SYSTIMESTAMP - INTERVAL '1' SECOND WHERE username = 'ops'");
        assertThat(accounts.recordSuccessUnlessLocked("ops")).isTrue();
        assertThat(accounts.find("ops").orElseThrow().failedAttempts()).isZero();
    }

    @Test
    void passwordsAndEnablementChange() {
        accounts.setPassword("ops", "$2a$10$other", false);
        accounts.setEnabled("ops", false);

        LocalAccount account = accounts.find("ops").orElseThrow();
        assertThat(account.passwordHash()).isEqualTo("$2a$10$other");
        assertThat(account.mustChangePassword()).isFalse();
        assertThat(account.enabled()).isFalse();
        assertThat(accounts.any()).isTrue();
        assertThat(accounts.find("nobody")).isEmpty();
    }
}
