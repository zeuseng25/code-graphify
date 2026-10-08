package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.audit.AuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(OutputCaptureExtension.class)
class BootstrapAdminTest extends OracleIntegrationTest {

    private static final String CONFIGURED = "Bootstrap-Test-Password-1";

    @Autowired
    BootstrapAdmin bootstrap;

    @Autowired
    AppUsers users;

    @Autowired
    LocalAccounts accounts;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    AuditLog auditLog;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void restoreConfiguredAdmin() {
        removeAdmin();
        bootstrap.ensureAdmin();
    }

    /** Removes every local account (and its user), so ensureAdmin sees a first start. */
    private void removeAdmin() {
        jdbc.update("DELETE FROM local_account");
        jdbc.update("DELETE FROM app_user WHERE source = 'LOCAL'");
    }

    @Test
    void theFirstStartCreatesALocalAdminThatMustChangeItsPassword() {
        AppUser admin = users.findByUsername(BootstrapAdmin.USERNAME).orElseThrow();
        LocalAccount account = accounts.find(BootstrapAdmin.USERNAME).orElseThrow();

        assertThat(admin.role()).isEqualTo(Role.ADMIN);
        assertThat(admin.source()).isEqualTo(UserSource.LOCAL);
        assertThat(admin.roleGrantedBy()).isEqualTo(BootstrapAdmin.ACTOR);
        assertThat(account.mustChangePassword()).isTrue();
        assertThat(encoder.matches(CONFIGURED, account.passwordHash())).isTrue();
        assertThat(bootstrap.ensureAdmin()).isEmpty();
    }

    @Test
    void withoutAConfiguredPasswordOneIsGeneratedAndLoggedOnce(CapturedOutput output) {
        removeAdmin();
        BootstrapAdmin unconfigured = new BootstrapAdmin("", users, accounts, encoder, auditLog, transactionManager);

        String generated = unconfigured.ensureAdmin().orElseThrow();

        assertThat(generated).hasSize(24);
        assertThat(encoder.matches(generated, accounts.find("admin").orElseThrow().passwordHash())).isTrue();
        assertThat(output.getOut() + output.getErr()).containsOnlyOnce(generated);
        assertThat(unconfigured.ensureAdmin()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'BOOTSTRAP_ADMIN_CREATED' "
                + "AND (details LIKE ? OR target LIKE ?)", Integer.class, "%" + generated + "%", "%" + generated + "%"))
                .isZero();
    }

    @Test
    void aFailureWhileCreatingTheAccountLeavesNoHalfCreatedAdmin() {
        removeAdmin();
        PasswordEncoder oversized = new PasswordEncoder() {
            @Override
            public String encode(CharSequence raw) {
                return "x".repeat(101); // local_account.password_hash is VARCHAR2(100 BYTE)
            }

            @Override
            public boolean matches(CharSequence raw, String encoded) {
                return false;
            }
        };
        BootstrapAdmin failing = new BootstrapAdmin(CONFIGURED, users, accounts, oversized, auditLog,
                transactionManager);

        assertThatThrownBy(failing::ensureAdmin).isInstanceOf(RuntimeException.class);

        assertThat(users.findByUsername(BootstrapAdmin.USERNAME)).isEmpty();
        assertThat(accounts.any()).isFalse();
    }

    @Test
    void aConfiguredPasswordLongerThanBcryptReadsFailsWithoutCreatingTheAdmin() {
        removeAdmin();
        String tooLong = "p".repeat(73);
        BootstrapAdmin misconfigured = new BootstrapAdmin(tooLong, users, accounts, encoder, auditLog,
                transactionManager);

        assertThatThrownBy(misconfigured::ensureAdmin).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_BOOTSTRAP_ADMIN_PASSWORD").hasMessageContaining("72 bytes")
                .message().doesNotContain(tooLong);
        assertThat(users.findByUsername(BootstrapAdmin.USERNAME)).isEmpty();
        assertThat(accounts.any()).isFalse();
    }
}
