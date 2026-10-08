package com.graphify.auth;

import java.time.Instant;

/** A row of app_user; holds no credentials. */
public record AppUser(
        long id,
        String username,
        UserSource source,
        String displayName,
        String email,
        Role role,
        String roleGrantedBy,
        Instant roleGrantedAt,
        Instant lastLoginAt,
        boolean active) {
}
