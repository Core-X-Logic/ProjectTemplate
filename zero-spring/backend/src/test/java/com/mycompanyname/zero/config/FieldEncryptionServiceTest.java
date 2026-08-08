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

    /**
     * GCM authentication must reject a modified ciphertext.
     *
     * <p><strong>The tampering happens on the DECODED bytes, not on a base64 character.</strong>
     * The earlier version flipped the character before the padding and was flaky at roughly 6%:
     * this payload is {@code IV(12) + ciphertext(16) + tag(16)} = 44 bytes, and {@code 44 % 3 == 2},
     * so the base64 form carries one {@code =} pad and the character before it holds only 4
     * meaningful bits — its low 2 bits are "don't care" and {@code Base64.getDecoder()} ignores
     * them. Whenever that character happened to be {@code A}-{@code D} (all four decode to the same
     * 4-bit value) the {@code A}↔{@code B} flip produced the SAME byte array: nothing was tampered
     * with, GCM accepted the value, and the assertion failed with "Expecting code to raise a
     * throwable".
     *
     * <p>The failure mode is worse than a flake: on the runs where it passed, the test may still
     * have been asserting on an unmodified payload. Flipping a byte is unambiguous at any length.
     */
    @Test
    void decryptingTamperedCiphertextThrows() {
        FieldEncryptionService service = service(KEY);
        byte[] payload = Base64.getDecoder().decode(service.encrypt("JBSWY3DPEHPK3PXP"));
        payload[payload.length / 2] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(payload);

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
