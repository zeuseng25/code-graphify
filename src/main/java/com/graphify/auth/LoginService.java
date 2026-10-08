package com.graphify.auth;

import com.graphify.audit.AuditAction;
import com.graphify.audit.AuditLog;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.ExternalSystemException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.LoginFailedException;
import com.graphify.common.util.Utf8;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Spec §7.1 login: an application-local account first; only when none exists, an LDAP bind. A first LDAP login
 * provisions the user with role USER. Every refusal looks the same to the caller; its reason is audited.
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    /** role_granted_by of users provisioned by their first directory login. */
    static final String PROVISIONING_ACTOR = "ldap-login";

    /** Width of audit_log.actor and audit_log.target (V1). */
    private static final int AUDIT_NAME_BYTES = 200;

    private final AppUsers users;
    private final LocalAccounts accounts;
    private final LdapDirectory directory;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final AuditLog auditLog;
    private final AppSettings settings;
    /** Burned against when no real hash exists, so unknown users cost as much as known ones. */
    private final String dummyHash;

    public LoginService(AppUsers users, LocalAccounts accounts, LdapDirectory directory, PasswordEncoder encoder,
            PasswordPolicy policy, AuditLog auditLog, AppSettings settings) {
        this.users = users;
        this.accounts = accounts;
        this.directory = directory;
        this.encoder = encoder;
        this.policy = policy;
        this.auditLog = auditLog;
        this.settings = settings;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public static Authentication authentication(AppPrincipal principal) {
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities());
    }

    public AppPrincipal login(String username, String password) {
        String name = AppUsers.normalize(username);
        if (name == null || name.isEmpty() || password == null || password.isEmpty()) {
            encoder.matches(password == null ? "" : password, dummyHash);
            throw failed(name == null || name.isEmpty() ? "-" : name, "blank username or password");
        }
        Optional<LocalAccount> local = accounts.find(name);
        if (local.isPresent()) {
            return loginLocal(local.get(), password);
        }
        if (!directory.enabled()) {
            encoder.matches(password, dummyHash);
            throw failed(name, "unknown user");
        }
        DirectoryUser entry = authenticateInDirectory(name, password)
                .orElseThrow(() -> failed(name, "rejected by the directory"));
        AppUser user = findOrProvision(entry);
        if (user.source() != UserSource.LDAP) {
            throw failed(name, "a local user of this name has no local account");
        }
        if (!user.active()) {
            throw failed(name, "inactive user");
        }
        users.recordLogin(user.id(), entry.displayName(), entry.email());
        return succeeded(principal(users.find(user.id()).orElseThrow(), false));
    }

    /** A concurrent first login of the same user may win the insert; then the stored row is used. */
    private AppUser findOrProvision(DirectoryUser entry) {
        Optional<AppUser> existing = users.findByUsername(entry.username());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return users.create(entry.username(), UserSource.LDAP, entry.displayName(), entry.email(), Role.USER,
                    PROVISIONING_ACTOR);
        } catch (DataIntegrityViolationException e) {
            return users.findByUsername(entry.username()).orElseThrow(() -> e);
        }
    }

    /** The directory's own message may name hosts and bind details; an anonymous caller only learns it is down. */
    private Optional<DirectoryUser> authenticateInDirectory(String name, String password) {
        try {
            return directory.authenticate(name, password);
        } catch (ExternalSystemException e) {
            log.warn("Directory login of {} failed: {}", loggable(name), e.getMessage());
            throw new ExternalSystemException("The directory is not available");
        }
    }

    private AppPrincipal loginLocal(LocalAccount account, String password) {
        String name = account.username();
        if (!account.enabled()) {
            encoder.matches(password, account.passwordHash());
            throw failed(name, "disabled account");
        }
        if (account.locked()) {
            encoder.matches(password, account.passwordHash());
            throw failed(name, "locked account");
        }
        if (!encoder.matches(password, account.passwordHash())) {
            boolean lockedNow = accounts.recordFailure(name, settings.getInt(SettingKeys.AUTH_MAX_FAILED_ATTEMPTS),
                    settings.getDuration(SettingKeys.AUTH_LOCK_DURATION));
            if (lockedNow) {
                auditLog.record(audited(name), AuditAction.ACCOUNT_LOCKED, audited(name),
                        "locked for " + settings.getDuration(SettingKeys.AUTH_LOCK_DURATION));
            }
            throw failed(name, "wrong password");
        }
        AppUser user = users.findByUsername(name).orElseThrow(() -> failed(name, "account without user"));
        if (!user.active()) {
            throw failed(name, "inactive user");
        }
        if (!accounts.recordSuccessUnlessLocked(name)) {
            throw failed(name, "locked account");
        }
        users.recordLogin(user.id(), null, null);
        return succeeded(principal(users.find(user.id()).orElseThrow(), account.mustChangePassword()));
    }

    public AppPrincipal changePassword(AppPrincipal who, String currentPassword, String newPassword) {
        if (who.source() != UserSource.LOCAL) {
            throw new ConflictException("The password of a directory user is managed in the directory");
        }
        LocalAccount account = accounts.find(who.username())
                .orElseThrow(() -> new ConflictException("No local account for " + who.username()));
        if (currentPassword == null || !encoder.matches(currentPassword, account.passwordHash())) {
            throw new InvalidRequestException("The current password is not correct");
        }
        policy.check(newPassword);
        reload(who.userId()).orElseThrow(() -> new ConflictException("The user is no longer active"));
        if (newPassword.equals(currentPassword)) {
            throw new InvalidRequestException("The new password must differ from the current one");
        }
        accounts.setPassword(who.username(), encoder.encode(newPassword), false);
        auditLog.record(audited(who.username()), AuditAction.PASSWORD_CHANGED, audited(who.username()), null);
        return reload(who.userId()).orElseThrow(() -> new ConflictException("The user is no longer active"));
    }

    /** The user as now stored; empty when missing, inactive, a disabled local account or LDAP is turned off. */
    public Optional<AppPrincipal> reload(long userId) {
        Optional<AppUser> user = users.find(userId).filter(AppUser::active);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        if (user.get().source() == UserSource.LOCAL) {
            Optional<LocalAccount> account = accounts.find(user.get().username());
            if (account.isEmpty() || !account.get().enabled()) {
                return Optional.empty();
            }
            return Optional.of(principal(user.get(), account.get().mustChangePassword()));
        }
        if (!directory.enabled()) {
            return Optional.empty();
        }
        return Optional.of(principal(user.get(), false));
    }

    private static AppPrincipal principal(AppUser user, boolean mustChangePassword) {
        return new AppPrincipal(user.id(), user.username(), user.displayName(), user.email(), user.role(),
                user.source(), mustChangePassword);
    }

    private AppPrincipal succeeded(AppPrincipal principal) {
        auditLog.record(audited(principal.username()), AuditAction.LOGIN_SUCCEEDED, audited(principal.username()),
                principal.source().name());
        return principal;
    }

    private LoginFailedException failed(String name, String reason) {
        auditLog.record(audited(name), AuditAction.LOGIN_FAILED, audited(name), reason);
        return new LoginFailedException();
    }

    private static String loggable(String name) {
        return name.replaceAll("\\p{Cntrl}", "_");
    }

    private static String audited(String name) {
        return Utf8.truncateToBytes(name, AUDIT_NAME_BYTES);
    }
}
