package com.graphify.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.auth.SecurityConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The real browser flow: fetch a CSRF token, sign in with it, fetch the new token; returns the session and token. */
public final class LoginFlow {

    public record Session(MockHttpSession session, String csrfToken) {
    }

    private LoginFlow() {
    }

    /**
     * Signs in with a token fetched before the login, then fetches the token again: a sign-in replaces the session's
     * token, so the returned one is the post-login token.
     */
    public static Session login(MockMvcTester anonymous, String username, String password) {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(anonymous, session);
        MvcTestResult login = anonymous.post().uri("/api/v1/auth/login").session(session)
                .header(SecurityConfiguration.CSRF_HEADER, token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}").exchange();
        assertThat(login).hasStatusOk();
        MockHttpSession signedIn = (MockHttpSession) login.getRequest().getSession(false);
        return new Session(signedIn, csrfToken(anonymous, signedIn));
    }

    /** The CSRF token the session holds now, as the client reads it from GET /api/v1/auth/csrf. */
    public static String csrfToken(MockMvcTester mvc, MockHttpSession session) {
        MvcTestResult csrf = mvc.get().uri("/api/v1/auth/csrf").session(session).exchange();
        assertThat(csrf).hasStatusOk();
        return JsonPath.read(body(csrf), "$.token");
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
