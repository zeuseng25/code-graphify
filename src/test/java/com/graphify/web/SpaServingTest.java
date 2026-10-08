package com.graphify.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.graphify.OracleIntegrationTest;
import com.graphify.auth.Role;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

class SpaServingTest extends OracleIntegrationTest {

    @Test
    void writesTheContextPathIntoTheBaseHref() {
        assertThat(anonymous().get().uri("/graphify/index.html").contextPath("/graphify")).hasStatusOk()
                .hasContentTypeCompatibleWith("text/html").bodyText()
                .contains("<base href=\"/graphify/\" />").contains("test-ui").doesNotContain("href=\"./\"");
        assertThat(anonymous().get().uri("/index.html")).hasStatusOk().bodyText().contains("<base href=\"/\" />");
        assertThat(anonymous().get().uri("/index.html")).headers().hasValue("Cache-Control", "no-store");
    }

    @Test
    void deepLinksAndTheRootForwardToTheApp() {
        assertThat(anonymous().get().uri("/repositories/12/graph")).hasForwardedUrl("/index.html");
        assertThat(anonymous().get().uri("/")).hasForwardedUrl("/index.html");
        assertThat(anonymous().get().uri("/graphify/admin/settings").contextPath("/graphify"))
                .hasForwardedUrl("/index.html");
    }

    @Test
    void apiPathsAndFilesAreNeverTheApp() {
        assertThat(as("viewer", Role.USER).get().uri("/api/v1/no-such-endpoint")).hasStatus(404)
                .hasContentTypeCompatibleWith("application/problem+json");
        assertThat(anonymous().get().uri("/api/v1/repositories")).hasStatus(401);
        assertThat(anonymous().get().uri("/assets/missing.js")).hasStatus(404).forwardedUrl().isNull();
        // an encoded /api is still the API: 401 with the forward filter, and by the security rules alone
        assertThat(anonymous().get().uri(URI.create("/%61pi/v1/repositories/1"))).hasStatus(401).forwardedUrl().isNull();
        MockMvcTester withoutFilter = MockMvcTester.from(context, b -> b.apply(springSecurity()).build());
        assertThat(withoutFilter.get().uri(URI.create("/%61pi/v1/repositories/1"))).hasStatus(401);
        assertThat(anonymous().head().uri("/")).hasStatusOk();
        // a non-GET outside /api is never the app: the CSRF check (403 problem) or denyAll rejects it
        assertThat(anonymous().post().uri("/repositories/12")).hasStatus(403);
        assertThat(mvc.post().uri("/repositories/12")).hasStatus(403);
    }

    @Test
    void hashedAssetsAreCachedForAYearAndTheIndexIsNot() {
        assertThat(anonymous().get().uri("/assets/test.js")).hasStatusOk().headers().hasValue("Cache-Control",
                "max-age=31536000, public, immutable");
    }

    @Test
    void everyResponseCarriesTheContentSecurityPolicy() {
        assertThat(anonymous().get().uri("/index.html")).headers().containsHeader("Content-Security-Policy");
        assertThat(anonymous().get().uri("/api/v1/auth/csrf")).headers().hasValue("Content-Security-Policy",
                WebConfiguration.CONTENT_SECURITY_POLICY);
        assertThat(anonymous().get().uri("/api/v1/repositories")).hasStatus(401).headers()
                .hasValue("Content-Security-Policy", WebConfiguration.CONTENT_SECURITY_POLICY);
    }
}
