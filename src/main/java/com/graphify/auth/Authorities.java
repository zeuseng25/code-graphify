package com.graphify.auth;

/** Granted authority names (spec §7.5). A user who must change their password holds only PASSWORD_CHANGE_REQUIRED. */
public final class Authorities {

    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_USER = "ROLE_USER";
    public static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";

    private Authorities() {
    }
}
