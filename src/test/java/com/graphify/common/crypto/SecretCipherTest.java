package com.graphify.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    @Test
    void roundTripsAndRandomizesEachEncryption() {
        SecretCipher cipher = new SecretCipher(KEY);

        String first = cipher.encrypt("bitbucket-token-şğü");
        String second = cipher.encrypt("bitbucket-token-şğü");

        assertThat(first).startsWith("v1:").isNotEqualTo(second).doesNotContain("bitbucket");
        assertThat(cipher.decrypt(first)).isEqualTo("bitbucket-token-şğü");
        assertThat(cipher.decrypt(second)).isEqualTo("bitbucket-token-şğü");
    }

    @Test
    void detectsTamperingWrongKeysAndUnknownFormats() {
        SecretCipher cipher = new SecretCipher(KEY);
        String stored = cipher.encrypt("secret");
        byte[] raw = Base64.getDecoder().decode(stored.substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(SecretDecryptionException.class);
        assertThatThrownBy(() -> new SecretCipher(OTHER_KEY).decrypt(stored))
                .isInstanceOf(SecretDecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt("plain-text")).isInstanceOf(SecretDecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt("v1:###")).isInstanceOf(SecretDecryptionException.class);
    }

    @Test
    void rejectsKeysThatAreNotBase64Of32Bytes() {
        assertThatThrownBy(() -> new SecretCipher("not base64 !"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_MASTER_KEY").hasNoCause();
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new SecretCipher("   "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_MASTER_KEY").hasNoCause();
    }
}
