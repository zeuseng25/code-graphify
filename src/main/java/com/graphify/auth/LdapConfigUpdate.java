package com.graphify.auth;

/** A full PUT document; {@code bindPassword} null keeps the stored secret and "" clears it (spec §6.4). */
public record LdapConfigUpdate(
        Boolean enabled,
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
        return "LdapConfigUpdate[enabled=" + enabled + ", url=" + url + ", baseDn=" + baseDn + "]";
    }
}
