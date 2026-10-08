package com.graphify.auth;

import com.graphify.audit.AuditAction;
import com.graphify.audit.AuditLog;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.secret.SecretUpdate;
import com.graphify.common.util.Utf8;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin management of the LDAP connection (spec §10.6 "LDAP"); secrets are never returned or audited. */
@Service
public class LdapAdministration {

    /** Widths of the ldap_config text columns in V6__authentication.sql. */
    private static final int TEXT_BYTES = 1000;
    private static final int ATTR_BYTES = 100;
    /** Keeps the encrypted bind password within bind_password_enc (VARCHAR2(4000 BYTE)). */
    private static final int PASSWORD_BYTES = 1000;

    /** Placeholder every configured filter must contain for the (encoded) user input. */
    private static final String PLACEHOLDER = "{0}";

    private final LdapConfigRepository config;
    private final LdapDirectory directory;
    private final AuditLog auditLog;
    private final AppUsers users;

    public LdapAdministration(LdapConfigRepository config, LdapDirectory directory, AuditLog auditLog,
            AppUsers users) {
        this.users = users;
        this.config = config;
        this.directory = directory;
        this.auditLog = auditLog;
    }

    public LdapConfigView get() {
        return config.view();
    }

    @Transactional
    public LdapConfigView update(LdapConfigUpdate update, String actor) {
        LdapSettings current = config.load();
        LdapSettings next = merge(update, current);
        if (current.enabled() && !next.enabled()) {
            requireLocalAdmin();
        }
        config.save(next, actor);
        auditLog.record(actor, AuditAction.LDAP_CONFIG_UPDATED, "ldap_config", "changed: " + String.join(", ",
                changedFields(current, next)));
        return config.view();
    }

    /** Disabling LDAP must leave an active local admin able to sign in; checked under the admin-row lock. */
    private void requireLocalAdmin() {
        boolean localAdmin = users.lockAdminsAnd(-1).stream().anyMatch(user -> user.source() == UserSource.LOCAL);
        if (!localAdmin) {
            throw new ConflictException("Disabling LDAP needs at least one active local admin");
        }
    }

    public void test(LdapConfigUpdate candidate) {
        directory.test(candidate == null ? config.load() : merge(candidate, config.load()));
    }

    private static LdapSettings merge(LdapConfigUpdate update, LdapSettings current) {
        if (update == null || update.enabled() == null) {
            throw new InvalidRequestException("enabled is required");
        }
        boolean enabled = update.enabled();
        String url = blankToNull(update.url());
        String baseDn = blankToNull(update.baseDn());
        if (url != null || enabled) {
            requireLdapUrl(url);
        }
        if (enabled) {
            if (baseDn == null) {
                throw new InvalidRequestException("baseDn is required when LDAP is enabled");
            }
        }
        String userSearchFilter = filter("userSearchFilter", update.userSearchFilter());
        String userQueryFilter = filter("userQueryFilter", update.userQueryFilter());
        String bindDn = blankToNull(update.bindDn());
        String password = SecretUpdate.resolve(update.bindPassword(), current.bindPassword(),
                Objects.equals(url, current.url()) && Objects.equals(bindDn, current.bindDn()), PASSWORD_BYTES,
                "bind password", "url or bindDn");
        LdapSettings next = new LdapSettings(enabled, url, baseDn, blankToNull(update.userSearchBase()),
                userSearchFilter, userQueryFilter, attribute("usernameAttr", update.usernameAttr()),
                attribute("displayNameAttr", update.displayNameAttr()), attribute("emailAttr", update.emailAttr()),
                bindDn, password);
        for (String text : new String[] {next.url(), next.baseDn(), next.userSearchBase(), next.bindDn()}) {
            fits(text, TEXT_BYTES);
        }
        return next;
    }

    private static void requireLdapUrl(String url) {
        if (url == null) {
            throw new InvalidRequestException("url is required when LDAP is enabled");
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (!"ldap".equalsIgnoreCase(scheme) && !"ldaps".equalsIgnoreCase(scheme)) {
                throw new InvalidRequestException("url must start with ldap:// or ldaps://");
            }
            if (uri.getUserInfo() != null) {
                throw new InvalidRequestException("url must not contain credentials; use bindDn and bindPassword");
            }
            if (uri.getHost() == null) {
                throw new InvalidRequestException("url must name a host, e.g. ldaps://ldap.example.com");
            }
        } catch (URISyntaxException e) {
            throw new InvalidRequestException("url is not a valid URI");
        }
    }

    private static String filter(String field, String value) {
        if (value == null || value.isBlank() || !value.contains(PLACEHOLDER)) {
            throw new InvalidRequestException(field + " must contain " + PLACEHOLDER);
        }
        return fits(value.strip(), TEXT_BYTES);
    }

    private static String attribute(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(field + " is required");
        }
        return fits(value.strip(), ATTR_BYTES);
    }

    private static String fits(String value, int maxBytes) {
        if (value != null && Utf8.byteLength(value) > maxBytes) {
            throw new InvalidRequestException("A value is longer than " + maxBytes + " bytes");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Names of the changed fields; the bind password is named, its value never shown. */
    private static List<String> changedFields(LdapSettings before, LdapSettings after) {
        List<String> changed = new ArrayList<>();
        if (before.enabled() != after.enabled()) {
            changed.add("enabled");
        }
        String[][] pairs = {
            {"url", before.url(), after.url()}, {"baseDn", before.baseDn(), after.baseDn()},
            {"userSearchBase", before.userSearchBase(), after.userSearchBase()},
            {"userSearchFilter", before.userSearchFilter(), after.userSearchFilter()},
            {"userQueryFilter", before.userQueryFilter(), after.userQueryFilter()},
            {"usernameAttr", before.usernameAttr(), after.usernameAttr()},
            {"displayNameAttr", before.displayNameAttr(), after.displayNameAttr()},
            {"emailAttr", before.emailAttr(), after.emailAttr()}, {"bindDn", before.bindDn(), after.bindDn()},
            {"bindPassword", before.bindPassword(), after.bindPassword()}};
        for (String[] pair : pairs) {
            if (!Objects.equals(pair[1], pair[2])) {
                changed.add(pair[0]);
            }
        }
        return changed.isEmpty() ? List.of("nothing") : changed;
    }
}
