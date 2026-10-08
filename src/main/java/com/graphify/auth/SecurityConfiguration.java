package com.graphify.auth;

import com.graphify.web.WebConfiguration;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * Spec §7.4–7.5: server-side sessions (HttpOnly cookie), a session-stored CSRF token sent back in a header, and the
 * role matrix. 401/403 answers are RFC 7807 problems.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /** Header that carries the CSRF token; the client reads the token from GET /api/v1/auth/csrf. */
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName(CSRF_HEADER);
        return repository;
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityContextRepository contexts,
            CsrfTokenRepository csrfTokens, LoginService logins) throws Exception {
        ProblemResponses problems = new ProblemResponses();
        String admin = Role.ADMIN.name();
        http.csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new HeaderOnlyCsrfTokenHandler()))
                .securityContext(context -> context.securityContextRepository(contexts))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .addFilterAfter(new CurrentUserRefreshFilter(logins, contexts, problems),
                        SecurityContextHolderFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/openapi.json").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/auth/me", "/api/v1/auth/logout", "/api/v1/auth/change-password")
                        .authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole(admin)
                        .requestMatchers(HttpMethod.POST, "/api/v1/index/runs", "/api/v1/index/runs/*/cancel")
                        .hasRole(admin)
                        .requestMatchers("/api", "/api/**").hasRole(Role.USER.name())
                        // the UI's files and routes carry no data; this permit must stay after every /api rule
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/**").permitAll()
                        .anyRequest().denyAll())
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp
                        .policyDirectives(WebConfiguration.CONTENT_SECURITY_POLICY)))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable());
        return http.build();
    }

    /**
     * Reads the token only from {@link #CSRF_HEADER}. The API takes JSON only, and the default handler would also
     * accept a {@code _csrf} request parameter, which a cross-site HTML form can send.
     */
    static final class HeaderOnlyCsrfTokenHandler extends CsrfTokenRequestAttributeHandler {

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            return request.getHeader(csrfToken.getHeaderName());
        }
    }
}
