package com.graphify.testsupport;

import com.graphify.auth.LdapSettings;
import com.graphify.common.crypto.SecretCipher;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldif.LDIFException;
import org.springframework.jdbc.core.JdbcTemplate;

/** One in-memory LDAP directory per test JVM, started on first use on a free port. */
public final class TestLdap {

    public static final String BASE_DN = "dc=corp,dc=com";
    public static final String BIND_DN = "cn=reader,dc=corp,dc=com";
    public static final String BIND_PASSWORD = "reader-pw";

    private static InMemoryDirectoryServer server;

    private TestLdap() {
    }

    public static synchronized String url() {
        if (server == null) {
            try {
                InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
                config.addAdditionalBindCredentials(BIND_DN, BIND_PASSWORD);
                config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0));
                InMemoryDirectoryServer started = new InMemoryDirectoryServer(config);
                started.add("dn: dc=corp,dc=com", "objectClass: top", "objectClass: domain", "dc: corp");
                started.add("dn: ou=people,dc=corp,dc=com", "objectClass: organizationalUnit", "ou: people");
                person(started, "ayse", "Ayşe Yılmaz", "Yılmaz", "ayse@corp.com", "ayse-secret");
                person(started, "mehmet", "Mehmet Kaya", "Kaya", "mehmet@corp.com", "mehmet-secret");
                person(started, "admin", "Directory Admin", "Admin", "dir-admin@corp.com", "ldap-admin-secret");
                started.startListening();
                server = started;
            } catch (LDAPException | LDIFException e) {
                throw new IllegalStateException(e);
            }
        }
        return "ldap://localhost:" + server.getListenPort();
    }

    private static void person(InMemoryDirectoryServer target, String uid, String cn, String sn, String mail,
            String password) throws LDAPException, LDIFException {
        target.add("dn: uid=" + uid + ",ou=people,dc=corp,dc=com", "objectClass: inetOrgPerson", "uid: " + uid,
                "cn: " + cn, "sn: " + sn, "mail: " + mail, "userPassword: " + password);
    }

    public static LdapSettings settings() {
        return new LdapSettings(true, url(), BASE_DN, "ou=people", "(uid={0})", "(|(uid={0}*)(cn={0}*)(mail={0}*))",
                "uid", "cn", "mail", BIND_DN, BIND_PASSWORD);
    }

    public static void configure(JdbcTemplate jdbc, SecretCipher cipher) {
        jdbc.update("""
                UPDATE ldap_config SET enabled = 1, url = ?, base_dn = ?, user_search_base = 'ou=people',
                       bind_dn = ?, bind_password_enc = ? WHERE id = 1
                """, url(), BASE_DN, BIND_DN, cipher.encrypt(BIND_PASSWORD));
    }

    public static void disable(JdbcTemplate jdbc) {
        jdbc.update("""
                UPDATE ldap_config SET enabled = 0, url = NULL, base_dn = NULL, user_search_base = NULL,
                       user_search_filter = '(uid={0})', user_query_filter = '(|(uid={0}*)(cn={0}*)(mail={0}*))',
                       username_attr = 'uid', display_name_attr = 'cn', email_attr = 'mail', bind_dn = NULL,
                       bind_password_enc = NULL, updated_by = NULL, updated_at = NULL
                 WHERE id = 1
                """);
    }
}
