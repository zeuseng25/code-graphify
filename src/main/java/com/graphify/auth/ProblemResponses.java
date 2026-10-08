package com.graphify.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * RFC 7807 bodies for the 401/403 the security filters produce (spec §8). Every text here is a constant, so the JSON
 * is written directly without escaping.
 */
final class ProblemResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", "Authentication is required", null);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        if (e instanceof CsrfException) {
            write(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "Missing or invalid CSRF token", "CSRF");
            return;
        }
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        boolean mustChange = current != null && current.getAuthorities().stream()
                .anyMatch(authority -> Authorities.PASSWORD_CHANGE_REQUIRED.equals(authority.getAuthority()));
        if (mustChange) {
            write(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "The password must be changed first",
                    Authorities.PASSWORD_CHANGE_REQUIRED);
            return;
        }
        write(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "Not allowed", null);
    }

    private static void write(HttpServletResponse response, int status, String title, String detail, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType(PROBLEM_JSON);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\"" + (code == null ? "" : ",\"code\":\"" + code + "\"") + "}");
    }
}
