package com.graphify.auth;

/**
 * The LDAP connection (ldap_config). {@code userSearchBase} is relative to {@code baseDn}; the filters contain the
 * placeholder {0}. toString never shows the bind password.
 */
public record LdapSettings(
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
        String bindPassword) {

    @Override
    public String toString() {
        return "LdapSettings[enabled=" + enabled + ", url=" + url + ", baseDn=" + baseDn + ", bindDn=" + bindDn + "]";
    }
}
