package com.mycompanyname.zero.config;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Authenticated symmetric encryption for column values that must be RECOVERABLE — as opposed to
 * password and recovery-code hashes, which are one-way. The single consumer today is the TOTP secret
 * stored on {@code users.two_factor_secret}: verifying a login code requires the original secret back,
 * so a hash will not do, but it must never sit in the database in clear text.
 *
 * <p>AES-256-GCM. GCM is authenticated (AEAD): {@link #decrypt} fails loudly on any tampered or
 * truncated ciphertext rather than returning garbage, which is what lets the 2FA verify path treat a
 * decrypt failure as a hard, fail-closed "no token". A fresh 12-byte IV is drawn per encryption and
 * prepended to the output, so encrypting the same secret twice yields different ciphertext and the IV
 * never has to be stored separately. Wire format, base64-encoded:
 * {@code [12-byte IV][ciphertext][16-byte GCM tag]}.
 *
 * <p>The key is resolved once at construction and its length checked there — the same fail-fast shape
 * as {@code JwtService.buildSecretKey} — so a mis-sized key breaks startup, not the first encryption.
 * {@link FieldEncryptionKeyValidator} has already vetted the same value against the active profiles by
 * the time this bean is built; the length check here is the second, context-free lock.
 */
@Service
public class FieldEncryptionService {

    /** AES-256: 32 bytes. */
    static final int KEY_BYTES = 32;
    /** NIST-recommended GCM nonce size. */
    private static final int IV_BYTES = 12;
    /** GCM authentication tag, in bits. */
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec key;

    public FieldEncryptionService(FieldEncryptionProperties properties) {
        this.key = buildKey(properties.getFieldKey());
    }

    /**
     * Encrypts {@code plaintext} and returns base64 of {@code IV || ciphertext || tag}. Never returns
     * a value equal to its input — the IV alone guarantees that — which is what the "secret at rest is
     * ciphertext" test asserts.
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv).put(ciphertext).array();
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Field encryption failed", ex);
        }
    }

    /**
     * Recovers the plaintext from {@link #encrypt}'s output. Throws on any input that is not valid,
     * authentic ciphertext for this key — a truncated blob, a wrong key, a flipped byte — because GCM
     * verifies the tag before releasing a single byte. Callers on security-critical paths catch this
     * and fail closed.
     */
    public String decrypt(String ciphertextBase64) {
        if (ciphertextBase64 == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(ciphertextBase64);
            if (all.length <= IV_BYTES) {
                throw new IllegalArgumentException("ciphertext too short to contain an IV");
            }
            ByteBuffer buffer = ByteBuffer.wrap(all);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Field decryption failed", ex);
        }
    }

    private static SecretKeySpec buildKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("zero.crypto.field-key is not configured");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "zero.crypto.field-key must be valid base64; raw text keys are not accepted", ex);
        }
        if (keyBytes.length != KEY_BYTES) {
            throw new IllegalStateException("zero.crypto.field-key must decode to exactly " + KEY_BYTES
                    + " bytes for AES-256-GCM, but was " + keyBytes.length + " bytes");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
