package com.graphify.auth;

import java.time.Instant;

/** A row of local_account; {@code locked} is true while locked_until lies in the future (database time). */
public record LocalAccount(
        String username,
        String passwordHash,
        boolean mustChangePassword,
        boolean enabled,
        int failedAttempts,
        Instant lockedUntil,
        boolean locked) {

    @Override
    public String toString() {
        return "LocalAccount[username=" + username + ", enabled=" + enabled + ", locked=" + locked + "]";
    }
}
