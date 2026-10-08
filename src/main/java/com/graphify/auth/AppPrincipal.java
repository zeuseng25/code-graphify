package com.graphify.auth;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** The signed-in user kept in the session; carries no credentials. */
public record AppPrincipal(
        long userId,
        String username,
        String displayName,
        String email,
        Role role,
        UserSource source,
        boolean mustChangePassword) implements AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
        return username;
    }

    public List<GrantedAuthority> authorities() {
        if (mustChangePassword) {
            return List.of(new SimpleGrantedAuthority(Authorities.PASSWORD_CHANGE_REQUIRED));
        }
        if (role == Role.ADMIN) {
            return List.of(new SimpleGrantedAuthority(Authorities.ROLE_ADMIN),
                    new SimpleGrantedAuthority(Authorities.ROLE_USER));
        }
        return List.of(new SimpleGrantedAuthority(Authorities.ROLE_USER));
    }
}
