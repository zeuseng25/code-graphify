package com.graphify.audit;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Locale;
import java.util.Optional;

/**
 * Every kind of entry the audit log records. The API lists them (OpenAPI enum), so the web UI offers them as a choice
 * with a Turkish label each instead of asking for a code.
 */
@Schema(enumAsRef = true)
public enum AuditAction {
    BOOTSTRAP_ADMIN_CREATED,
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    LOGOUT,
    PASSWORD_CHANGED,
    ACCOUNT_LOCKED,
    USER_REGISTERED,
    USER_ROLE_CHANGED,
    USER_ACTIVE_CHANGED,
    LDAP_CONFIG_UPDATED,
    SCM_CONNECTION_CREATED,
    SCM_CONNECTION_UPDATED,
    SCM_CONNECTION_DELETED,
    ARTIFACT_REPOSITORY_CREATED,
    ARTIFACT_REPOSITORY_UPDATED,
    ARTIFACT_REPOSITORY_DELETED,
    SETTING_UPDATED,
    ENTRY_POINT_ANNOTATION_CREATED,
    ENTRY_POINT_ANNOTATION_UPDATED,
    ENTRY_POINT_ANNOTATION_DELETED,
    IMPACT_RULE_UPDATED;

    /** The action a (case-insensitive) code names, or empty for an unknown code. */
    public static Optional<AuditAction> parse(String code) {
        String upper = code.strip().toUpperCase(Locale.ROOT);
        for (AuditAction action : values()) {
            if (action.name().equals(upper)) {
                return Optional.of(action);
            }
        }
        return Optional.empty();
    }
}
