package com.graphify.auth;

import com.graphify.audit.AuditAction;
import com.graphify.audit.AuditLog;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session endpoints (spec §10.2). */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** toString leaves the password out: request bodies may be logged at DEBUG. */
    public record Credentials(String username, String password) {

        @Override
        public String toString() {
            return "Credentials[username=" + username + "]";
        }
    }

    /** toString leaves both passwords out: request bodies may be logged at DEBUG. */
    public record PasswordChange(String currentPassword, String newPassword) {

        @Override
        public String toString() {
            return "PasswordChange[]";
        }
    }

    public record CsrfView(String headerName, String token) {
    }

    public record Me(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String username, String displayName,
            String email, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Role role, UserSource source,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean mustChangePassword) {

        static Me of(AppPrincipal principal) {
            return new Me(principal.username(), principal.displayName(), principal.email(), principal.role(),
                    principal.source(), principal.mustChangePassword());
        }
    }

    private final LoginService logins;
    private final SecurityContextRepository contexts;
    private final AppSettings settings;
    private final AuditLog auditLog;
    private final CsrfTokenRepository csrfTokens;

    public AuthController(LoginService logins, SecurityContextRepository contexts, AppSettings settings,
            AuditLog auditLog, CsrfTokenRepository csrfTokens) {
        this.logins = logins;
        this.contexts = contexts;
        this.settings = settings;
        this.auditLog = auditLog;
        this.csrfTokens = csrfTokens;
    }

    @GetMapping("/csrf")
    public CsrfView csrf(CsrfToken token) {
        return new CsrfView(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    public Me login(@RequestBody(required = false) Credentials credentials, HttpServletRequest request,
            HttpServletResponse response) {
        if (credentials == null) {
            throw new InvalidRequestException("A body {username, password} is required");
        }
        AppPrincipal principal = logins.login(credentials.username(), credentials.password());
        establish(principal, request, response);
        request.getSession().setMaxInactiveInterval(
                (int) settings.getDuration(SettingKeys.AUTH_SESSION_TIMEOUT).toSeconds());
        return Me.of(principal);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication authentication, HttpServletRequest request) {
        auditLog.record(authentication.getName(), AuditAction.LOGOUT, authentication.getName(), null);
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Me me(Authentication authentication) {
        if (authentication.getPrincipal() instanceof AppPrincipal principal) {
            return Me.of(principal);
        }
        boolean admin = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(Authorities.ROLE_ADMIN::equals);
        return new Me(authentication.getName(), null, null, admin ? Role.ADMIN : Role.USER, null, false);
    }

    @PostMapping("/change-password")
    public Me changePassword(@RequestBody(required = false) PasswordChange change, Authentication authentication,
            HttpServletRequest request, HttpServletResponse response) {
        if (change == null) {
            throw new InvalidRequestException("A body {currentPassword, newPassword} is required");
        }
        if (!(authentication.getPrincipal() instanceof AppPrincipal principal)) {
            throw new ConflictException("Only signed-in application users can change a password");
        }
        AppPrincipal changed = logins.changePassword(principal, change.currentPassword(), change.newPassword());
        establish(changed, request, response);
        return Me.of(changed);
    }

    /**
     * Stores the new authentication under a new session id and a new CSRF token. The session keeps its attributes
     * across {@code changeSessionId()}, so without a new token one fetched before the change would stay valid; both
     * renewals prevent fixation of the session and of the token.
     */
    private void establish(AppPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(LoginService.authentication(principal));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);
    }
}
