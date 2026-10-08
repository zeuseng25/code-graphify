package com.graphify.common.secret;

import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.util.Utf8;

/**
 * The secret field of a full-document update (spec §6.4): null keeps the stored secret, but only for the same target,
 * so a stored credential is never sent to a server or account it was not set for; "" clears it.
 */
public final class SecretUpdate {

    private SecretUpdate() {
    }

    public static String resolve(String submitted, String stored, boolean sameTarget, int maxBytes, String secretName,
            String targetName) {
        if (submitted == null) {
            if (stored != null && !sameTarget) {
                throw new InvalidRequestException("Re-enter the " + secretName + " when changing " + targetName);
            }
            return stored;
        }
        if (submitted.isEmpty()) {
            return null;
        }
        if (Utf8.byteLength(submitted) > maxBytes) {
            throw new InvalidRequestException("The " + secretName + " is longer than " + maxBytes + " bytes");
        }
        return submitted;
    }
}
