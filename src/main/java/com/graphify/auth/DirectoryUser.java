package com.graphify.auth;

/** A person as the directory describes them; {@code username} is normalized. */
public record DirectoryUser(String username, String displayName, String email) {
}
