package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.testsupport.TestLdap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class LdapAdminApiTest extends OracleIntegrationTest {

    @Autowired
    SecretCipher cipher;

    @Autowired
    LdapConfigRepository config;

    @AfterEach
    void tearDown() {
        TestLdap.disable(jdbc);
        jdbc.update("DELETE FROM audit_log WHERE action = 'LDAP_CONFIG_UPDATED'");
    }

    private String body(boolean enabled, String url, String bindPassword) {
        return """
                {"enabled":%s,"url":"%s","baseDn":"dc=corp,dc=com","userSearchBase":"ou=people",
                 "userSearchFilter":"(uid={0})","userQueryFilter":"(|(uid={0}*)(cn={0}*))","usernameAttr":"uid",
                 "displayNameAttr":"cn","emailAttr":"mail","bindDn":"cn=reader,dc=corp,dc=com"%s}
                """.formatted(enabled, url, bindPassword == null ? "" : ",\"bindPassword\":\"" + bindPassword + "\"");
    }

    @Test
    void savesAndShowsTheConfigurationWithoutTheBindPassword() {
        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, TestLdap.url(), TestLdap.BIND_PASSWORD))).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.enabled").isEqualTo(true);
                    assertThat(json).extractingPath("$.bindPasswordSet").isEqualTo(true);
                    assertThat(json).extractingPath("$.updatedBy").isEqualTo(TEST_ACTOR);
                });

        assertThat(config.load().bindPassword()).isEqualTo(TestLdap.BIND_PASSWORD);
        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, TestLdap.url(), null))).hasStatusOk();
        assertThat(config.load().bindPassword()).isEqualTo(TestLdap.BIND_PASSWORD);
        assertThat(mvc.post().uri("/api/v1/admin/ldap/test")).hasStatusOk().bodyJson()
                .extractingPath("$.ok").isEqualTo(true);
    }

    @Test
    void theBindPasswordIsNeverReturnedOrAudited() {
        mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, TestLdap.url(), TestLdap.BIND_PASSWORD)).exchange();

        assertThat(mvc.get().uri("/api/v1/admin/ldap")).hasStatusOk().bodyText()
                .doesNotContain(TestLdap.BIND_PASSWORD).doesNotContain(cipher.encrypt(TestLdap.BIND_PASSWORD));
        assertThat(jdbc.queryForList("SELECT NVL(details, '-') FROM audit_log WHERE action = 'LDAP_CONFIG_UPDATED'",
                String.class)).isNotEmpty().noneMatch(details -> details.contains(TestLdap.BIND_PASSWORD))
                .anyMatch(details -> details.contains("bindPassword"));
        assertThat(mvc.post().uri("/api/v1/admin/ldap/test").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, "ldap://localhost:1", "candidate-secret"))).hasStatus(502).bodyText()
                .doesNotContain("candidate-secret");
    }

    @Test
    void aUrlWithUserinfoIsRejectedWithoutEchoingIt() {
        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, "ldap://user:pw@host:389", "x"))).hasStatus(400).bodyText()
                .doesNotContain("pw@").contains("must not contain credentials");
    }

    @Test
    void rejectsAnUnusableConfiguration() {
        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, "http://not-ldap", "x"))).hasStatus(400);
        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, TestLdap.url(), "x").replace("(uid={0})", "(uid=fixed)"))).hasStatus(400);
        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"ldap://x\"}")).hasStatus(400);
        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(false, "", null))).hasStatusOk();
    }

    @Test
    void onlyAdminsManageTheDirectory() {
        assertThat(as("viewer", Role.USER).get().uri("/api/v1/admin/ldap")).hasStatus(403);
    }

    @Test
    void aChangedUrlOrBindDnNeedsTheBindPasswordAgain() {
        mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, TestLdap.url(), TestLdap.BIND_PASSWORD)).exchange();

        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, "ldap://attacker:389", null))).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/admin/ldap/test").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, "ldap://attacker:389", null))).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/admin/ldap/test").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, TestLdap.url(), null).replace("cn=reader", "cn=other"))).hasStatus(400);
        assertThat(config.load().url()).isEqualTo(TestLdap.url());
        assertThat(config.load().bindPassword()).isEqualTo(TestLdap.BIND_PASSWORD);
    }

    @Test
    void anEmptyBindPasswordClearsTheSecret() {
        mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, TestLdap.url(), TestLdap.BIND_PASSWORD)).exchange();

        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, TestLdap.url(), ""))).hasStatusOk().bodyJson()
                .extractingPath("$.bindPasswordSet").isEqualTo(false);
        assertThat(config.load().bindPassword()).isNull();
    }

    @Test
    void validatesTheUrlSchemeEvenWhenDisabledAndCapsThePasswordLength() {
        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(false, "http://x", null))).hasStatus(400);
        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, TestLdap.url(), "p".repeat(1001)))).hasStatus(400);
    }

    @Test
    void aWrongCandidatePasswordLeaksNeitherPassword() {
        mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, TestLdap.url(), TestLdap.BIND_PASSWORD)).exchange();

        assertThat(mvc.post().uri("/api/v1/admin/ldap/test").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, TestLdap.url(), "wrong-candidate"))).hasStatus(502).bodyText()
                .doesNotContain("wrong-candidate").doesNotContain(TestLdap.BIND_PASSWORD);
    }

    @Test
    void disablingLdapNeedsAnActiveLocalAdmin() {
        mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, TestLdap.url(), TestLdap.BIND_PASSWORD)).exchange();
        jdbc.update("INSERT INTO app_user (username, source, role) VALUES ('ldap-admin', 'LDAP', 'ADMIN')");
        jdbc.update("UPDATE app_user SET active = 0 WHERE source = 'LOCAL' AND role = 'ADMIN'");
        try {
            assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                    .content(body(false, TestLdap.url(), null))).hasStatus(409);
            assertThat(config.load().enabled()).isTrue();
        } finally {
            jdbc.update("UPDATE app_user SET active = 1 WHERE source = 'LOCAL' AND role = 'ADMIN'");
            jdbc.update("DELETE FROM app_user WHERE username = 'ldap-admin'");
        }
    }

    @Test
    void nonAdminsCannotChangeOrTestTheDirectory() {
        assertThat(as("viewer", Role.USER).put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, TestLdap.url(), null))).hasStatus(403);
        assertThat(as("viewer", Role.USER).post().uri("/api/v1/admin/ldap/test")).hasStatus(403);
    }

    @Test
    void withNoStoredSecretAUrlNeedsNoPassword() {
        assertThat(config.load().bindPassword()).isNull();
        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(false, "ldap://somewhere:389", null))).hasStatusOk();
        assertThat(config.load().bindPassword()).isNull();
    }
}
