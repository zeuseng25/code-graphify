package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.ExternalSystemException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.LoginFailedException;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.AuthFixtures;
import com.graphify.testsupport.SettingsOverride;
import com.graphify.testsupport.TestLdap;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

class LoginServiceTest extends OracleIntegrationTest {

    private static final String OPS_PASSWORD = "ops-password-123";

    @Autowired
    LoginService logins;

    @Autowired
    AppUsers users;

    @Autowired
    LocalAccounts accounts;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    SecretCipher cipher;

    @Autowired
    AppSettings settings;

    private SettingsOverride overrides;

    @BeforeEach
    void setUp() {
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
        AuthFixtures.createLocal(users, accounts, encoder.encode(OPS_PASSWORD), "ops", Role.ADMIN, false);
        overrides = new SettingsOverride(settings);
        TestLdap.configure(jdbc, cipher);
    }

    @AfterEach
    void tearDown() {
        overrides.restore();
        TestLdap.disable(jdbc);
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
        accounts.recordSuccess("admin"); // the shadowing test counts a failure against the bootstrap admin
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'LOGIN%' OR action IN ('ACCOUNT_LOCKED', "
                + "'PASSWORD_CHANGED')");
    }

    @Test
    void aLocalAccountSignsInCaseInsensitively() {
        AppPrincipal principal = logins.login(" OPS ", OPS_PASSWORD);

        assertThat(principal.username()).isEqualTo("ops");
        assertThat(principal.role()).isEqualTo(Role.ADMIN);
        assertThat(principal.authorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly(Authorities.ROLE_ADMIN, Authorities.ROLE_USER);
        assertThat(users.findByUsername("ops").orElseThrow().lastLoginAt()).isNotNull();
    }

    @Test
    void repeatedFailuresLockTheAccountAndEveryFailureLooksTheSame() {
        overrides.set(SettingKeys.AUTH_MAX_FAILED_ATTEMPTS, "2");

        assertThatThrownBy(() -> logins.login("ops", "wrong-1")).isInstanceOf(LoginFailedException.class)
                .hasMessage(LoginFailedException.MESSAGE);
        assertThatThrownBy(() -> logins.login("ops", "wrong-2")).hasMessage(LoginFailedException.MESSAGE);
        assertThatThrownBy(() -> logins.login("ops", OPS_PASSWORD)).hasMessage(LoginFailedException.MESSAGE);
        assertThatThrownBy(() -> logins.login("nobody-at-all", "x")).hasMessage(LoginFailedException.MESSAGE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCOUNT_LOCKED' "
                + "AND target = 'ops'", Integer.class)).isEqualTo(1);

        jdbc.update("UPDATE local_account SET locked_until = SYSTIMESTAMP - INTERVAL '1' SECOND WHERE username = 'ops'");

        assertThat(logins.login("ops", OPS_PASSWORD).username()).isEqualTo("ops");
        assertThat(accounts.find("ops").orElseThrow().failedAttempts()).isZero();
    }

    @Test
    void theFirstDirectoryLoginProvisionsAUserAndLaterLoginsKeepTheRole() {
        AppPrincipal first = logins.login("Ayse", "ayse-secret");

        assertThat(first.role()).isEqualTo(Role.USER);
        assertThat(first.source()).isEqualTo(UserSource.LDAP);
        assertThat(first.displayName()).isEqualTo("Ayşe Yılmaz");
        AppUser stored = users.findByUsername("ayse").orElseThrow();
        assertThat(stored.roleGrantedBy()).isEqualTo("ldap-login");

        users.setRole(stored.id(), Role.ADMIN, "ops");

        assertThat(logins.login("ayse", "ayse-secret").role()).isEqualTo(Role.ADMIN);
        assertThatThrownBy(() -> logins.login("ayse", "wrong")).isInstanceOf(LoginFailedException.class);
    }

    @Test
    void aLocalAccountShadowsTheDirectoryAccountOfTheSameName() {
        assertThatThrownBy(() -> logins.login("admin", "ldap-admin-secret")).isInstanceOf(LoginFailedException.class);
        assertThat(accounts.find("admin").orElseThrow().failedAttempts()).isEqualTo(1);
    }

    @Test
    void aLocalUserWithoutALocalAccountCannotSignInThroughTheDirectory() {
        users.create("mehmet", UserSource.LOCAL, "Mehmet", null, Role.USER, "test");

        assertThatThrownBy(() -> logins.login("mehmet", "mehmet-secret")).isInstanceOf(LoginFailedException.class);
    }

    @Test
    void aLockSetAfterTheSnapshotStillRefusesTheCorrectPassword() {
        LocalAccount stale = accounts.find("ops").orElseThrow();
        accounts.recordFailure("ops", 1, Duration.ofMinutes(15));
        assertThat(stale.locked()).isFalse();

        assertThat(accounts.recordSuccessUnlessLocked("ops")).isFalse();
        assertThatThrownBy(() -> logins.login("ops", OPS_PASSWORD)).isInstanceOf(LoginFailedException.class);
    }

    @Test
    void inactiveUsersCannotSignIn() {
        logins.login("mehmet", "mehmet-secret");
        users.setActive(users.findByUsername("mehmet").orElseThrow().id(), false);
        users.setActive(users.findByUsername("ops").orElseThrow().id(), false);

        assertThatThrownBy(() -> logins.login("mehmet", "mehmet-secret")).isInstanceOf(LoginFailedException.class);
        assertThatThrownBy(() -> logins.login("ops", OPS_PASSWORD)).isInstanceOf(LoginFailedException.class);
        assertThat(logins.reload(users.findByUsername("ops").orElseThrow().id())).isEmpty();
    }

    @Test
    void withoutLdapOnlyLocalAccountsSignIn() {
        TestLdap.disable(jdbc);

        assertThatThrownBy(() -> logins.login("ayse", "ayse-secret")).isInstanceOf(LoginFailedException.class);
        assertThat(logins.login("ops", OPS_PASSWORD).username()).isEqualTo("ops");
    }

    @Test
    void anUnreachableDirectoryIsNotALoginFailure() {
        jdbc.update("UPDATE ldap_config SET url = 'ldap://localhost:1' WHERE id = 1");

        assertThatThrownBy(() -> logins.login("ayse", "ayse-secret")).isInstanceOf(ExternalSystemException.class)
                .hasMessage("The directory is not available");
    }

    @Test
    void aPasswordChangeClearsTheMustChangeFlag() {
        AuthFixtures.createLocal(users, accounts, encoder.encode("initial-password-1"), "fresh", Role.USER, true);
        AppPrincipal principal = logins.login("fresh", "initial-password-1");
        assertThat(principal.authorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly(Authorities.PASSWORD_CHANGE_REQUIRED);

        assertThatThrownBy(() -> logins.changePassword(principal, "wrong", "brand-new-password-1"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> logins.changePassword(principal, "initial-password-1", "short"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> logins.changePassword(principal, "initial-password-1", "initial-password-1"))
                .isInstanceOf(InvalidRequestException.class);

        AppPrincipal changed = logins.changePassword(principal, "initial-password-1", "brand-new-password-1");

        assertThat(changed.mustChangePassword()).isFalse();
        assertThat(changed.authorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly(Authorities.ROLE_USER);
        assertThat(logins.login("fresh", "brand-new-password-1").username()).isEqualTo("fresh");

        AppPrincipal directory = logins.login("ayse", "ayse-secret");
        assertThatThrownBy(() -> logins.changePassword(directory, "ayse-secret", "brand-new-password-2"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void auditNeverContainsPasswords() {
        logins.login("ops", OPS_PASSWORD);
        assertThatThrownBy(() -> logins.login("ops", "Wrong-Secret-42"));
        logins.login("ayse", "ayse-secret");

        assertThat(jdbc.queryForList("SELECT actor || ' ' || target || ' ' || NVL(details, '') FROM audit_log "
                + "WHERE action LIKE 'LOGIN%'", String.class))
                .isNotEmpty()
                .noneMatch(row -> row.contains(OPS_PASSWORD) || row.contains("Wrong-Secret-42")
                        || row.contains("ayse-secret"));
    }
}
