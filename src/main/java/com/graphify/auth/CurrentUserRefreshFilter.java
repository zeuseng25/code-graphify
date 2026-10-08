package com.graphify.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Re-reads the signed-in user on every request (one primary-key lookup), so a role change or deactivation applies to
 * open sessions at once (spec §7.4) instead of at the next login. Not a bean: it is registered only in the security
 * filter chain, never as a plain servlet filter. A session of a missing or inactive user ends with a 401.
 */
final class CurrentUserRefreshFilter extends OncePerRequestFilter {

    private final LoginService logins;
    private final SecurityContextRepository contexts;
    private final AuthenticationEntryPoint entryPoint;

    CurrentUserRefreshFilter(LoginService logins, SecurityContextRepository contexts,
            AuthenticationEntryPoint entryPoint) {
        this.logins = logins;
        this.contexts = contexts;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current != null && current.getPrincipal() instanceof AppPrincipal principal) {
            Optional<AppPrincipal> fresh = logins.reload(principal.userId());
            if (fresh.isEmpty()) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                // answered here: going on would meet the CSRF check (its token was in the session) and give a 403
                entryPoint.commence(request, response,
                        new InsufficientAuthenticationException("The signed-in user is no longer active"));
                return;
            } else if (!fresh.get().equals(principal)) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(LoginService.authentication(fresh.get()));
                SecurityContextHolder.setContext(context);
                contexts.saveContext(context, request, response);
            }
        }
        chain.doFilter(request, response);
    }
}
