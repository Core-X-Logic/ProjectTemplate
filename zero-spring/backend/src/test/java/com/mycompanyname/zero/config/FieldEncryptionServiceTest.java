package com.mycompanyname.zero.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip and fail-safety of the AES-256-GCM field encryptor. The fail-loud behaviour on tampered
 * input is what lets the 2FA verify path treat a decrypt failure as a hard "no token".
 */
class FieldEncryptionServiceTest {

    /** The committed test key (32 bytes, base64). */
    private static final String KEY = "emVyby1GSUVMRC1FTkMtVEVTVC1uZXZlci1kZXBsb3k=";

    private static FieldEncryptionService service(String base64Key) {
        FieldEncryptionProperties properties = new FieldEncryptionProperties();
        properties.setFieldKey(base64Key);
        return new FieldEncryptionService(properties);
    }

    @Test
    void encryptThenDecryptRoundTrips() {
        FieldEncryptionService service = service(KEY);
        String secret = "JBSWY3DPEHPK3PXP";
        assertThat(service.decrypt(service.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void ciphertextNeverEqualsPlaintextAndDiffersEachCall() {
        FieldEncryptionService service = service(KEY);
        String secret = "JBSWY3DPEHPK3PXP";
        String first = service.encrypt(secret);
        String second = service.encrypt(secret);
        assertThat(first).isNotEqualTo(secret);
        // Fresh IV per call: same plaintext must not produce identical ciphertext.
        assertThat(first).isNotEqualTo(second);
        assertThat(service.decrypt(first)).isEqualTo(secret);
        assertThat(service.decrypt(second)).isEqualTo(secret);
    }

    @Test
    void nullIsPassedThroughBothWays() {
        FieldEncryptionService service = service(KEY);
        assertThat(service.encrypt(null)).isNull();
        assertThat(service.decrypt(null)).isNull();
    }

    @Test
    void decryptingTamperedCiphertextThrows() {
        FieldEncryptionService service = service(KEY);
        String ciphertext = service.encrypt("JBSWY3DPEHPK3PXP");
        // flip the last character of the base64 body — GCM authentication must reject it
        String tampered = ciphertext.substring(0, ciphertext.length() - 2)
                + (ciphertext.charAt(ciphertext.length() - 2) == 'A' ? 'B' : 'A')
                + ciphertext.charAt(ciphertext.length() - 1);
        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption failed");
    }

    @Test
    void decryptingGarbageThrows() {
        FieldEncryptionService service = service(KEY);
        assertThatThrownBy(() -> service.decrypt("not-valid-ciphertext"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption failed");
    }

    @Test
    void aWrongLengthKeyIsRejectedAtConstruction() {
        String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> service(sixteenBytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32 bytes");
    }
}
