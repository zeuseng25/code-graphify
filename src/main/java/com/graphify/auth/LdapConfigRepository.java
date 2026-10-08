package com.graphify.auth;

import com.graphify.common.crypto.SecretCipher;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The single ldap_config row (spec §7.2); the bind password is stored encrypted. */
@Repository
public class LdapConfigRepository {

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public LdapConfigRepository(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public LdapSettings load() {
        return jdbc.queryForObject("""
                SELECT enabled, url, base_dn, user_search_base, user_search_filter, user_query_filter, username_attr,
                       display_name_attr, email_attr, bind_dn, bind_password_enc
                  FROM ldap_config WHERE id = 1
                """, (rs, row) -> {
                    String secret = rs.getString("bind_password_enc");
                    return new LdapSettings(rs.getInt("enabled") == 1, rs.getString("url"), rs.getString("base_dn"),
                            rs.getString("user_search_base"), rs.getString("user_search_filter"),
                            rs.getString("user_query_filter"), rs.getString("username_attr"),
                            rs.getString("display_name_attr"), rs.getString("email_attr"), rs.getString("bind_dn"),
                            secret == null ? null : cipher.decrypt(secret));
                });
    }

    /** Whether LDAP sign-in is on; reads only the flag, so per-request and locked checks stay cheap. */
    public boolean enabled() {
        Integer enabled = jdbc.queryForObject("SELECT enabled FROM ldap_config WHERE id = 1", Integer.class);
        return enabled != null && enabled == 1;
    }

    public LdapConfigView view() {
        return jdbc.queryForObject("""
                SELECT enabled, url, base_dn, user_search_base, user_search_filter, user_query_filter, username_attr,
                       display_name_attr, email_attr, bind_dn, bind_password_enc, updated_by, updated_at
                  FROM ldap_config WHERE id = 1
                """, (rs, row) -> {
                    OffsetDateTime updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
                    return new LdapConfigView(rs.getInt("enabled") == 1, rs.getString("url"), rs.getString("base_dn"),
                            rs.getString("user_search_base"), rs.getString("user_search_filter"),
                            rs.getString("user_query_filter"), rs.getString("username_attr"),
                            rs.getString("display_name_attr"), rs.getString("email_attr"), rs.getString("bind_dn"),
                            rs.getString("bind_password_enc") != null, rs.getString("updated_by"),
                            updatedAt == null ? null : updatedAt.toInstant());
                });
    }

    public void save(LdapSettings settings, String actor) {
        String password = settings.bindPassword();
        jdbc.update("""
                UPDATE ldap_config SET enabled = ?, url = ?, base_dn = ?, user_search_base = ?, user_search_filter = ?,
                       user_query_filter = ?, username_attr = ?, display_name_attr = ?, email_attr = ?, bind_dn = ?,
                       bind_password_enc = ?, updated_by = ?, updated_at = SYSTIMESTAMP
                 WHERE id = 1
                """, settings.enabled() ? 1 : 0, settings.url(), settings.baseDn(), settings.userSearchBase(),
                settings.userSearchFilter(), settings.userQueryFilter(), settings.usernameAttr(),
                settings.displayNameAttr(), settings.emailAttr(), settings.bindDn(),
                password == null || password.isEmpty() ? null : cipher.encrypt(password), actor);
    }
}
