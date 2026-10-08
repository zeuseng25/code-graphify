package com.graphify.auth;

import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.util.Utf8;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import org.springframework.stereotype.Component;

/** Rules a new local-account password must meet; the minimum length comes from auth.password_min_length. */
@Component
public class PasswordPolicy {

    /** bcrypt reads only the first 72 bytes of a password; longer ones are refused rather than cut. */
    public static final int MAX_BYTES = 72;

    private final AppSettings settings;

    public PasswordPolicy(AppSettings settings) {
        this.settings = settings;
    }

    public void check(String newPassword) {
        int minimum = settings.getInt(SettingKeys.AUTH_PASSWORD_MIN_LENGTH);
        if (newPassword == null || newPassword.length() < minimum) {
            throw new InvalidRequestException("The new password must be at least " + minimum + " characters long");
        }
        if (Utf8.byteLength(newPassword) > MAX_BYTES) {
            throw new InvalidRequestException("The new password must be at most " + MAX_BYTES + " bytes long (UTF-8)");
        }
    }
}
