package com.graphify.scm;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/** Bitbucket DC accepts an HTTP access token as {@code Bearer}, or user + password/token as {@code Basic}. */
public final class AuthorizationHeader {

    private AuthorizationHeader() {
    }

    public static Optional<String> of(String username, String secret) {
        if (secret == null) {
            return Optional.empty();
        }
        if (username == null || username.isBlank()) {
            return Optional.of("Bearer " + secret);
        }
        String pair = username.strip() + ":" + secret;
        return Optional.of("Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
    }
}
