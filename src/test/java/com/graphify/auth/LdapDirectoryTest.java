package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.ExternalSystemException;
import com.graphify.testsupport.TestLdap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LdapDirectoryTest extends OracleIntegrationTest {

    @Autowired
    LdapDirectory directory;

    @Autowired
    LdapConfigRepository config;

    @Autowired
    SecretCipher cipher;

    @BeforeEach
    void setUp() {
        TestLdap.configure(jdbc, cipher);
    }

    @AfterEach
    void tearDown() {
        TestLdap.disable(jdbc);
    }

    @Test
    void loadsTheConfigurationWithTheDecryptedBindPassword() {
        LdapSettings loaded = config.load();

        assertThat(loaded.enabled()).isTrue();
        assertThat(loaded.bindPassword()).isEqualTo(TestLdap.BIND_PASSWORD);
        assertThat(loaded.toString()).doesNotContain(TestLdap.BIND_PASSWORD);
    }

    @Test
    void authenticatesWithTheDirectoryPassword() {
        assertThat(directory.authenticate("Ayse", "ayse-secret"))
                .contains(new DirectoryUser("ayse", "Ayşe Yılmaz", "ayse@corp.com"));
        assertThat(directory.authenticate("ayse", "wrong")).isEmpty();
        assertThat(directory.authenticate("nobody", "x")).isEmpty();
        assertThat(directory.authenticate("ayse", "")).isEmpty();
    }

    @Test
    void searchesAndFindsUsers() {
        assertThat(directory.search("me")).extracting(DirectoryUser::username).containsExactly("mehmet");
        assertThat(directory.search("Ay")).extracting(DirectoryUser::displayName).containsExactly("Ayşe Yılmaz");
        assertThat(directory.search(" ")).isEmpty();
        assertThat(directory.find("MEHMET")).contains(new DirectoryUser("mehmet", "Mehmet Kaya", "mehmet@corp.com"));
        assertThat(directory.find("ghost")).isEmpty();
    }

    @Test
    void filterMetacharactersNeverWiden() {
        assertThat(directory.search("*")).isEmpty();
        assertThat(directory.search("*)(uid=*")).isEmpty();
        assertThat(directory.find("*")).isEmpty();
        assertThat(directory.authenticate("*", "ayse-secret")).isEmpty();
        assertThat(directory.authenticate("ayse)(|(uid=*", "ayse-secret")).isEmpty();
    }

    @Test
    void anUnreachableDirectoryIsAnExternalFailureWithoutSecrets() {
        jdbc.update("UPDATE ldap_config SET url = 'ldap://localhost:1' WHERE id = 1");

        assertThatThrownBy(() -> directory.authenticate("ayse", "ayse-secret"))
                .isInstanceOf(ExternalSystemException.class)
                .hasMessageNotContaining(TestLdap.BIND_PASSWORD)
                .hasMessageNotContaining("ayse-secret");
        assertThatThrownBy(() -> directory.test(new LdapSettings(true, "ldap://localhost:1", TestLdap.BASE_DN,
                "ou=people", "(uid={0})", "(uid={0}*)", "uid", "cn", "mail", TestLdap.BIND_DN, "x")))
                .isInstanceOf(ExternalSystemException.class);
    }

    @Test
    void testingChecksTheBindAccount() {
        directory.test(TestLdap.settings());

        LdapSettings wrongPassword = new LdapSettings(true, TestLdap.url(), TestLdap.BASE_DN, "ou=people",
                "(uid={0})", "(uid={0}*)", "uid", "cn", "mail", TestLdap.BIND_DN, "nope");
        assertThatThrownBy(() -> directory.test(wrongPassword)).isInstanceOf(ExternalSystemException.class)
                .hasMessageNotContaining("nope");
    }

    @Test
    void aDisabledDirectoryCannotBeUsed() {
        TestLdap.disable(jdbc);

        assertThat(directory.enabled()).isFalse();
        assertThatThrownBy(() -> directory.search("ay")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> directory.authenticate("ayse", "ayse-secret")).isInstanceOf(ConflictException.class);
    }
}
