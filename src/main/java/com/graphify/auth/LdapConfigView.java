package com.graphify.auth;

import java.time.Instant;

/** The LDAP configuration as the API shows it: never the bind password, only whether one is set (spec §6.4). */
public record LdapConfigView(
        boolean enabled,
        String url,
        String baseDn,
        String userSearchBase,
        String userSearchFilter,
        String userQueryFilter,
        String usernameAttr,
        String displayNameAttr,
        String emailAttr,
        String bindDn,
        boolean bindPasswordSet,
        String updatedBy,
        Instant updatedAt) {
}
