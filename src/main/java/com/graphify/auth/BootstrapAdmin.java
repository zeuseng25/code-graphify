package com.graphify.auth;

import com.graphify.audit.AuditLog;
import com.graphify.common.util.Utf8;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spec §7.3 first start: when no local account exists, creates the local {@code admin} with the ADMIN role and a
 * password that must be changed at first login. The password comes from APP_BOOTSTRAP_ADMIN_PASSWORD, or is generated
 * and written to the log exactly once (the spec's one sanctioned secret in a log).
 */
@Component
public class BootstrapAdmin implements SmartInitializingSingleton {

    /** Username of the emergency local admin (spec §7.3). */
    public static final String USERNAME = "admin";

    /** role_granted_by / audit actor of the bootstrap step. */
    public static final String ACTOR = "bootstrap";

    /** Random bytes behind a generated password; 18 bytes are 24 URL-safe Base64 characters. */
    private static final int GENERATED_BYTES = 18;

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final String configuredPassword;
    private final AppUsers users;
    private final LocalAccounts accounts;
    private final PasswordEncoder encoder;
    private final AuditLog auditLog;
    private final TransactionTemplate transaction;

    @Autowired
    public BootstrapAdmin(@Value("${app.bootstrap-admin-password:}") String configuredPassword, AppUsers users,
            LocalAccounts accounts, PasswordEncoder encoder, AuditLog auditLog,
            PlatformTransactionManager transactionManager) {
        this.configuredPassword = configuredPassword;
        this.users = users;
        this.accounts = accounts;
        this.encoder = encoder;
        this.auditLog = auditLog;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void afterSingletonsInstantiated() {
        ensureAdmin();
    }

    /**
     * Creates the admin when needed; returns the password only when it was generated here. The user, the account and
     * the audit row are written in one transaction, so a failure never leaves a user without an account.
     */
    public Optional<String> ensureAdmin() {
        if (configuredPassword != null && Utf8.byteLength(configuredPassword) > PasswordPolicy.MAX_BYTES) {
            throw new IllegalStateException("APP_BOOTSTRAP_ADMIN_PASSWORD is longer than " + PasswordPolicy.MAX_BYTES
                    + " bytes (UTF-8), which bcrypt cannot hash; set a shorter one");
        }
        Created created = transaction.execute(status -> createAdmin());
        if (created == null || !created.created()) {
            return Optional.empty();
        }
        if (created.generatedPassword() != null) {
            log.warn("Created the local admin account '{}' with the one-time password: {} (it must be changed at the "
                    + "first login)", USERNAME, created.generatedPassword());
            return Optional.of(created.generatedPassword());
        }
        log.info("Created the local admin account '{}' with the password from APP_BOOTSTRAP_ADMIN_PASSWORD", USERNAME);
        return Optional.empty();
    }

    private Created createAdmin() {
        if (accounts.any()) {
            return new Created(false, null);
        }
        if (users.findByUsername(USERNAME).isPresent()) {
            log.warn("No local account exists, but a user named '{}' does; no bootstrap admin was created", USERNAME);
            return new Created(false, null);
        }
        boolean generate = configuredPassword == null || configuredPassword.isBlank();
        String password = generate ? generatePassword() : configuredPassword;
        users.create(USERNAME, UserSource.LOCAL, "Administrator", null, Role.ADMIN, ACTOR);
        accounts.create(USERNAME, encoder.encode(password), true);
        auditLog.record(ACTOR, "BOOTSTRAP_ADMIN_CREATED", USERNAME, generate ? "password generated" : "password from "
                + "APP_BOOTSTRAP_ADMIN_PASSWORD");
        return new Created(true, generate ? password : null);
    }

    /** Outcome of the transactional step; the password is logged only after the commit. */
    private record Created(boolean created, String generatedPassword) {
    }

    private static String generatePassword() {
        byte[] bytes = new byte[GENERATED_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
