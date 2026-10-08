package com.graphify;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.graphify.auth.Role;
import com.graphify.web.SpaForwardFilter;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;

/** Base for tests against the real Oracle schema and MVC layer. All subclasses share one context and container. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public abstract class OracleIntegrationTest {

    /** The user {@link #mvc} requests run as. */
    protected static final String TEST_ACTOR = "tester";

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected WebApplicationContext context;

    @Autowired
    private CsrfTokenRepository csrfTokens;

    /** Requests as {@value #TEST_ACTOR} with the ADMIN and USER roles and a valid CSRF token. */
    protected MockMvcTester mvc;

    @BeforeEach
    void signedInTester() {
        mvc = as(TEST_ACTOR, Role.ADMIN, Role.USER);
    }

    protected MockMvcTester as(String username, Role... roles) {
        String[] names = Arrays.stream(roles).map(Role::name).toArray(String[]::new);
        return MockMvcTester.from(context, builder -> builder.apply(springSecurity())
                .addFilters(new SpaForwardFilter())
                .defaultRequest(get("/").with(user(username).roles(names)).with(validCsrfToken())).build());
    }

    /**
     * A token from the application's own repository, sent in its header. spring-security-test's {@code csrf()} is not
     * used: it replaces the repository inside the shared CsrfFilter with a default one (header X-CSRF-TOKEN), which
     * would break the real header for every later request in the shared context.
     */
    private RequestPostProcessor validCsrfToken() {
        return request -> {
            CsrfToken token = csrfTokens.generateToken(request);
            csrfTokens.saveToken(token, request, new MockHttpServletResponse());
            request.addHeader(token.getHeaderName(), token.getToken());
            return request;
        };
    }

    protected MockMvcTester anonymous() {
        // MockMvcTester.from does not pick up the application's servlet filters; the SPA forward runs after security
        return MockMvcTester.from(context,
                builder -> builder.apply(springSecurity()).addFilters(new SpaForwardFilter()).build());
    }
}
