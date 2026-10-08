package com.graphify.common.exception;

/**
 * A login was refused. The message is the same for every reason (unknown user, wrong password, locked, inactive)
 * so the response never tells which; the reason is audited instead.
 */
public class LoginFailedException extends RuntimeException {

    public static final String MESSAGE = "Invalid username or password";

    public LoginFailedException() {
        super(MESSAGE);
    }
}
