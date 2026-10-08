package com.graphify.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts stored credentials (tokens, LDAP bind password) with AES-256-GCM. The key comes from the
 * {@code APP_MASTER_KEY} environment variable and never from the database it protects (spec §6.1).
 * Stored form: {@code v1:} + Base64(12-byte IV ‖ ciphertext ‖ 16-byte tag).
 */
@Component
public class SecretCipher {

    private static final String FORMAT_PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${app.master-key}") String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            // Not chained: the decoder's message quotes a character of the key.
            throw new IllegalStateException("APP_MASTER_KEY (app.master-key) must be Base64-encoded");
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("APP_MASTER_KEY (app.master-key) must decode to " + KEY_BYTES
                    + " bytes but decoded to " + raw.length + "; generate one with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] stored = ByteBuffer.allocate(IV_BYTES + encrypted.length).put(iv).put(encrypted).array();
            return FORMAT_PREFIX + Base64.getEncoder().encodeToString(stored);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(FORMAT_PREFIX)) {
            throw new SecretDecryptionException("Not an encrypted secret (missing " + FORMAT_PREFIX + " prefix)", null);
        }
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(FORMAT_PREFIX.length()));
            if (raw.length <= IV_BYTES) {
                throw new SecretDecryptionException("Encrypted secret is truncated", null);
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new SecretDecryptionException("Secret could not be decrypted with the current APP_MASTER_KEY", e);
        }
    }
}
