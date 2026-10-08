package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.testsupport.AuthFixtures;
import com.graphify.testsupport.TestLdap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class UserAdminApiTest extends OracleIntegrationTest {

    @Autowired
    AppUsers users;

    @Autowired
    SecretCipher cipher;

    @BeforeEach
    void setUp() {
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
        TestLdap.configure(jdbc, cipher);
    }

    @AfterEach
    void tearDown() {
        TestLdap.disable(jdbc);
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
    }

    @Test
    void searchesTheDirectoryAndRegistersAnAdmin() {
        assertThat(mvc.get().uri("/api/v1/admin/ldap/users?q=ay")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$[0].username").isEqualTo("ayse");
            assertThat(json).extractingPath("$[0].appUserId").isNull();
        });

        MvcTestResult created = mvc.post().uri("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ayse\",\"role\":\"ADMIN\"}").exchange();

        assertThat(created).hasStatus(201);
        long id = users.findByUsername("ayse").orElseThrow().id();
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/admin/users/" + id);
        assertThat(mvc.get().uri("/api/v1/admin/ldap/users?q=ay")).hasStatusOk().bodyJson()
                .extractingPath("$[0].appUserId").isEqualTo((int) id);
        assertThat(jdbc.queryForObject("SELECT role_granted_by FROM app_user WHERE id = ?", String.class, id))
                .isEqualTo(TEST_ACTOR);
    }

    @Test
    void changesRolesAndActivityAndKeepsTheLastAdmin() {
        long admin = users.findByUsername("admin").orElseThrow().id();

        assertThat(mvc.put().uri("/api/v1/admin/users/" + admin + "/role").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"USER\"}")).hasStatus(409);
        assertThat(mvc.put().uri("/api/v1/admin/users/" + admin + "/active").contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")).hasStatus(409);
        assertThat(mvc.put().uri("/api/v1/admin/users/-1/role").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"USER\"}")).hasStatus(404);
        assertThat(mvc.put().uri("/api/v1/admin/users/" + admin + "/role").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"BOSS\"}")).hasStatus(400);
        assertThat(mvc.get().uri("/api/v1/admin/users?q=adm")).hasStatusOk().bodyJson()
                .extractingPath("$.items[0].username").isEqualTo("admin");
    }

    @Test
    void usersCannotAdministerUsers() {
        assertThat(as("viewer", Role.USER).get().uri("/api/v1/admin/users")).hasStatus(403);
    }
}
