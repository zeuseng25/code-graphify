package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.AuthFixtures;
import com.graphify.testsupport.LoginFlow;
import com.graphify.testsupport.TestLdap;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class SecurityApiTest extends OracleIntegrationTest {

    @Autowired
    AppUsers users;

    @Autowired
    LocalAccounts accounts;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    SecretCipher cipher;

    @Autowired
    AppSettings settings;

    private MockMvcTester anonymous;

    @BeforeEach
    void setUp() {
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
        TestLdap.configure(jdbc, cipher);
        anonymous = anonymous();
    }

    @AfterEach
    void tearDown() {
        TestLdap.disable(jdbc);
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
    }

    @Test
    void anonymousCallersGetAProblemAndOnlyOpenEndpointsAnswer() {
        assertThat(anonymous.get().uri("/api/v1/symbols/search?q=Price")).hasStatus(401).bodyJson()
                .extractingPath("$.title").isEqualTo("Unauthorized");
        assertThat(anonymous.get().uri("/api/v1/openapi.json")).hasStatusOk();
        assertThat(anonymous.get().uri("/api/v1/auth/csrf")).hasStatusOk().bodyJson()
                .extractingPath("$.headerName").isEqualTo(SecurityConfiguration.CSRF_HEADER);
        assertThat(anonymous.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ops\",\"password\":\"x\"}")).hasStatus(403).bodyJson()
                .extractingPath("$.code").isEqualTo("CSRF");
        // outside /api a GET is the web UI (served without sign-in); anything else is still refused
        assertThat(anonymous.get().uri("/not-an-api")).hasStatusOk();
        assertThat(anonymous.post().uri("/not-an-api")).hasStatus(403);
        assertThat(mvc.post().uri("/not-an-api")).hasStatus(403);
    }

    @Test
    void aDirectoryUserSignsInGetsUserRightsAndSignsOut() {
        LoginFlow.Session session = LoginFlow.login(anonymous, "ayse", "ayse-secret");

        assertThat(anonymous.get().uri("/api/v1/auth/me").session(session.session())).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.username").isEqualTo("ayse");
                    assertThat(json).extractingPath("$.role").isEqualTo("USER");
                    assertThat(json).extractingPath("$.displayName").isEqualTo("Ayşe Yılmaz");
                });
        assertThat(session.session().getMaxInactiveInterval())
                .isEqualTo((int) settings.getDuration(SettingKeys.AUTH_SESSION_TIMEOUT).toSeconds());
        assertThat(anonymous.get().uri("/api/v1/repositories").session(session.session())).hasStatusOk();
        assertThat(anonymous.post().uri("/api/v1/index/runs").session(session.session())
                .header(SecurityConfiguration.CSRF_HEADER, session.csrfToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"ALL\"}")).hasStatus(403);
        assertThat(anonymous.post().uri("/api/v1/impact").session(session.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"symbolIds\":[1]}")).hasStatus(403).bodyJson()
                .extractingPath("$.code").isEqualTo("CSRF");

        assertThat(anonymous.post().uri("/api/v1/auth/logout").session(session.session())
                .header(SecurityConfiguration.CSRF_HEADER, session.csrfToken())).hasStatus(204);
        assertThat(anonymous.get().uri("/api/v1/auth/me").session(session.session())).hasStatus(401);
    }

    @Test
    void signingInReplacesTheSessionIdAndTheCsrfTokenAndOnlyTheHeaderCarriesIt() {
        MockHttpSession session = new MockHttpSession();
        String before = LoginFlow.csrfToken(anonymous, session);
        String idBefore = session.getId();

        MvcTestResult login = anonymous.post().uri("/api/v1/auth/login").session(session)
                .header(SecurityConfiguration.CSRF_HEADER, before).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ayse\",\"password\":\"ayse-secret\"}").exchange();
        assertThat(login).hasStatusOk();
        MockHttpSession signedIn = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(signedIn.getId()).isNotEqualTo(idBefore);
        String after = LoginFlow.csrfToken(anonymous, signedIn);
        assertThat(after).isNotEqualTo(before);

        assertThat(anonymous.post().uri("/api/v1/auth/logout").session(signedIn)
                .header(SecurityConfiguration.CSRF_HEADER, before)).hasStatus(403).bodyJson()
                .extractingPath("$.code").isEqualTo("CSRF");
        assertThat(anonymous.post().uri("/api/v1/auth/logout").session(signedIn).param("_csrf", after))
                .hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("CSRF");
        assertThat(anonymous.post().uri("/api/v1/auth/logout").session(signedIn)
                .header(SecurityConfiguration.CSRF_HEADER, after)).hasStatus(204);
    }

    @Test
    void wrongCredentialsAreA401Problem() {
        MockMvcTester fresh = anonymous();
        MockHttpSession session = new MockHttpSession();
        String token = JsonPath.read(new String(fresh.get().uri("/api/v1/auth/csrf").session(session).exchange()
                .getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), "$.token");

        assertThat(fresh.post().uri("/api/v1/auth/login").session(session)
                .header(SecurityConfiguration.CSRF_HEADER, token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ayse\",\"password\":\"wrong\"}")).hasStatus(401).bodyJson()
                .extractingPath("$.detail").isEqualTo("Invalid username or password");
    }

    @Test
    void aMustChangePasswordUserCanOnlyChangeThePassword() {
        AuthFixtures.createLocal(users, accounts, encoder.encode("initial-password-1"), "fresh", Role.ADMIN, true);
        LoginFlow.Session session = LoginFlow.login(anonymous, "fresh", "initial-password-1");

        assertThat(anonymous.get().uri("/api/v1/repositories").session(session.session())).hasStatus(403)
                .bodyJson().extractingPath("$.code").isEqualTo("PASSWORD_CHANGE_REQUIRED");
        assertThat(anonymous.get().uri("/api/v1/auth/me").session(session.session())).hasStatusOk().bodyJson()
                .extractingPath("$.mustChangePassword").isEqualTo(true);

        assertThat(anonymous.post().uri("/api/v1/auth/change-password").session(session.session())
                .header(SecurityConfiguration.CSRF_HEADER, session.csrfToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"initial-password-1\",\"newPassword\":\"brand-new-password-1\"}"))
                .hasStatusOk().bodyJson().extractingPath("$.mustChangePassword").isEqualTo(false);

        assertThat(anonymous.get().uri("/api/v1/repositories").session(session.session())).hasStatusOk();
        assertThat(anonymous.post().uri("/api/v1/auth/logout").session(session.session())
                .header(SecurityConfiguration.CSRF_HEADER, session.csrfToken())).hasStatus(403).bodyJson()
                .extractingPath("$.code").isEqualTo("CSRF");
        assertThat(anonymous.post().uri("/api/v1/auth/logout").session(session.session())
                .header(SecurityConfiguration.CSRF_HEADER, LoginFlow.csrfToken(anonymous, session.session())))
                .hasStatus(204);
    }

    @Test
    void roleAndActiveChangesApplyToOpenSessions() {
        LoginFlow.Session session = LoginFlow.login(anonymous, "mehmet", "mehmet-secret");
        long id = users.findByUsername("mehmet").orElseThrow().id();
        assertThat(anonymous.get().uri("/api/v1/index/runs").session(session.session())).hasStatusOk();
        assertThat(anonymous.post().uri("/api/v1/index/runs/-1/cancel").session(session.session())
                .header(SecurityConfiguration.CSRF_HEADER, session.csrfToken())).hasStatus(403);

        users.setRole(id, Role.ADMIN, "test");

        assertThat(anonymous.post().uri("/api/v1/index/runs/-1/cancel").session(session.session())
                .header(SecurityConfiguration.CSRF_HEADER, session.csrfToken())).hasStatus(404);

        users.setActive(id, false);

        assertThat(anonymous.post().uri("/api/v1/index/runs/-1/cancel").session(session.session())
                .header(SecurityConfiguration.CSRF_HEADER, session.csrfToken())).hasStatus(401).bodyJson()
                .extractingPath("$.detail").isEqualTo("Authentication is required");
        assertThat(anonymous.get().uri("/api/v1/index/runs").session(session.session())).hasStatus(401);
    }

    @Test
    void theTestBaseRequestsRunAsAnAdmin() {
        assertThat(mvc.get().uri("/api/v1/auth/me")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.username").isEqualTo(TEST_ACTOR);
            assertThat(json).extractingPath("$.role").isEqualTo("ADMIN");
        });
        assertThat(as("viewer", Role.USER).post().uri("/api/v1/index/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"ALL\"}")).hasStatus(403);
    }

    @Test
    void disablingLdapEndsOpenDirectorySessions() {
        LoginFlow.Session session = LoginFlow.login(anonymous, "ayse", "ayse-secret");
        assertThat(anonymous.get().uri("/api/v1/auth/me").session(session.session())).hasStatusOk();

        // the bootstrap admin is an active local admin, so LDAP may be disabled
        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON).content("""
                {"enabled":false,"userSearchFilter":"(uid={0})","userQueryFilter":"(uid={0}*)","usernameAttr":"uid",
                 "displayNameAttr":"cn","emailAttr":"mail","bindPassword":""}
                """)).hasStatusOk();

        assertThat(anonymous.get().uri("/api/v1/auth/me").session(session.session())).hasStatus(401);
    }
}
