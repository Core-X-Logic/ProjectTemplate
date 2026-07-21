package com.mycompanyname.zero.identity.auth;

import com.mycompanyname.zero.config.JwtProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The resolved HS512 signing/verification key ring (PROD-R16, key rotation half).
 *
 * <p>Owns the <em>structural and cryptographic</em> validation of the ring and fails boot fast on a
 * broken configuration: every secret must be valid base64 of at least 64 bytes (HS512's minimum), no
 * unresolved {@code ${...}} placeholder, every kid non-blank and unique, and exactly one
 * {@code active-kid} that is actually present. The complementary <em>profile-policy</em> validation
 * (the committed dev/leaked keys are refused under {@code prod}) lives in {@code JwtSecretValidator},
 * which is where the active profiles are known — the two together are the full boot guard.
 *
 * <p><b>Backward compatibility.</b> A deployment that sets only the legacy {@code zero.jwt.secret}
 * (no {@code keys}) gets a one-key ring synthesised for it under {@link #DEFAULT_LEGACY_KID}, marked
 * active. Nothing about such a deployment changes: it signs with that single key and, because the
 * decoder's no-kid fallback also resolves to the active key, tokens minted by the pre-rotation code
 * (which carried no {@code kid}) keep verifying.
 *
 * <p><b>Rotation procedure (zero downtime).</b>
 * <ol>
 *   <li>Add the new key to {@code zero.jwt.keys} (a new kid + secret). Deploy. Every instance can now
 *       <em>verify</em> the new key even though none signs with it yet.</li>
 *   <li>Flip {@code zero.jwt.active-kid} to the new kid. Deploy. New tokens are signed with it; tokens
 *       still bearing the old kid keep verifying because the old key is still in the ring.</li>
 *   <li>Wait for a grace window ≥ the access-token TTL, so every token signed by the old key has
 *       expired.</li>
 *   <li>Remove the old key from {@code zero.jwt.keys}. Deploy. A token bearing the retired kid now
 *       fails closed (unknown kid → rejected).</li>
 * </ol>
 */
@Component
public class JwtKeyRing {

    /** HS512 requires a key of at least 512 bits (64 bytes). */
    static final int MIN_SECRET_KEY_BYTES = 64;

    /** The kid assigned to the synthesised single-key ring when only the legacy secret is set. */
    public static final String DEFAULT_LEGACY_KID = "legacy";

    private final String activeKid;
    private final SecretKeySpec activeKey;
    private final Map<String, SecretKeySpec> keysByKid;

    public JwtKeyRing(JwtProperties properties) {
        List<JwtProperties.Key> configured = properties.getKeys();
        if (configured != null && !configured.isEmpty()) {
            this.keysByKid = resolveRing(configured);
            String active = properties.getActiveKid();
            if (active == null || active.isBlank()) {
                throw new IllegalStateException(
                        "zero.jwt.active-kid must be set when zero.jwt.keys is configured");
            }
            if (!keysByKid.containsKey(active)) {
                throw new IllegalStateException("zero.jwt.active-kid '" + active
                        + "' is not present in zero.jwt.keys; the active key must be one of "
                        + keysByKid.keySet());
            }
            this.activeKid = active;
        } else {
            // Backward-compatible: synthesise a one-key ring from the legacy single secret.
            this.activeKid = DEFAULT_LEGACY_KID;
            this.keysByKid = Map.of(DEFAULT_LEGACY_KID, buildKey(DEFAULT_LEGACY_KID, properties.getSecret()));
        }
        this.activeKey = keysByKid.get(activeKid);
    }

    private static Map<String, SecretKeySpec> resolveRing(List<JwtProperties.Key> configured) {
        Map<String, SecretKeySpec> resolved = new LinkedHashMap<>();
        for (JwtProperties.Key key : configured) {
            String kid = key.getKid();
            if (kid == null || kid.isBlank()) {
                throw new IllegalStateException("every zero.jwt.keys entry must have a non-blank kid");
            }
            if (resolved.containsKey(kid)) {
                throw new IllegalStateException("zero.jwt.keys contains a duplicate kid '" + kid + "'");
            }
            resolved.put(kid, buildKey(kid, key.getSecret()));
        }
        return Map.copyOf(resolved);
    }

    /**
     * Builds the HS512 key for {@code kid}. Fails fast (at bean init, so at boot) on a missing,
     * placeholder, non-base64 or too-short secret rather than silently degrading key strength.
     */
    static SecretKeySpec buildKey(String kid, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT signing key '" + kid + "' has no secret configured");
        }
        if (secret.startsWith("${") && secret.endsWith("}")) {
            throw new IllegalStateException("JWT signing key '" + kid + "' is an unresolved placeholder ("
                    + secret + "); the environment variable it points at was not set");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("JWT signing key '" + kid
                    + "' must be valid base64; raw text secrets are not accepted", ex);
        }
        if (keyBytes.length < MIN_SECRET_KEY_BYTES) {
            throw new IllegalStateException("JWT signing key '" + kid + "' must decode to at least "
                    + MIN_SECRET_KEY_BYTES + " bytes for HS512, but was " + keyBytes.length + " bytes");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA512");
    }

    /** The kid the encoder stamps into the {@code kid} header of every newly issued token. */
    public String activeKid() {
        return activeKid;
    }

    /**
     * The JWK source the encoder signs from: the active key exposed as an {@link OctetSequenceKey}
     * carrying {@link #activeKid} and the HS512 algorithm, so the header's {@code kid} matches a JWK in
     * the set (a header kid with no matching JWK would fail encoding).
     */
    public JWKSource<SecurityContext> signingJwkSource() {
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(activeKey)
                .keyID(activeKid)
                .algorithm(JWSAlgorithm.HS512)
                .build();
        return new ImmutableJWKSet<>(new JWKSet(jwk));
    }

    /**
     * The verification key(s) the decoder should try for a token whose header carries {@code kid}.
     *
     * <ul>
     *   <li><b>Known kid</b> → that key. Verify with exactly the key that signed it.</li>
     *   <li><b>Missing kid</b> ({@code null}/blank) → the active key. A token from the pre-rotation
     *       code carried no kid; during a rolling deploy those in-flight tokens must still verify, and
     *       the active key is the legacy key in the common single-key case.</li>
     *   <li><b>Unknown kid</b> (present but not in the ring — a retired key) → no key, i.e. an empty
     *       list, which makes Nimbus reject the signature. Fail-closed on purpose.</li>
     * </ul>
     */
    public List<SecretKey> verificationKeys(String kid) {
        SecretKeySpec key = (kid == null || kid.isBlank()) ? activeKey : keysByKid.get(kid);
        return key == null ? List.of() : List.of(key);
    }
}
