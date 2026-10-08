package com.graphify.common.crypto;

/** A stored secret could not be decrypted: wrong master key, tampering, or not produced by {@link SecretCipher}. */
public class SecretDecryptionException extends RuntimeException {

    public SecretDecryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
