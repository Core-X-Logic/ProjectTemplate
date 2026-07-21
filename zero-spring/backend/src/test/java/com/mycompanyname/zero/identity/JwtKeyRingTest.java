package com.mycompanyname.zero.identity;

import com.mycompanyname.zero.config.JwtProperties;
import com.mycompanyname.zero.identity.auth.JwtKeyRing;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Structural and crypto validation of the PROD-R16 key ring, plus the kid-selection rules the decoder
 * leans on. No Spring context — {@link JwtKeyRing} is a plain resolver over {@link JwtProperties}.
 */
class JwtKeyRingTest {

    // Two independent 64-byte HS512 secrets, base64. Distinct from every committed dev/test key.
    private static final String KEY_A =
            "0RHdZWWiWSkAy7eqRHT/VAloxKrgRO5gtwRSkNqx9lwG3ijPrAWdJaCHzbzJ+/PLyc1HnFUsw6CY4R+f5/pPcQ==";
    private static final String KEY_B =
            "2S/3JQku8aklmMRxJ4KJ/YZKaw/KKsmu8QHeoUzVCdSSueLW3+XgNwxi2uCDyHwT8+7biKR5dD+esj+BqLZD5w==";

    @Test
    void aLoneLegacySecretSynthesisesAOneKeyRingUnderTheDefaultKid() {
        JwtProperties props = new JwtProperties();
        props.setSecret(KEY_A);

        JwtKeyRing ring = new JwtKeyRing(props);

        assertThat(ring.activeKid()).isEqualTo(JwtKeyRing.DEFAULT_LEGACY_KID);
        // a token bearing the default kid verifies with the one key
        assertThat(ring.verificationKeys(JwtKeyRing.DEFAULT_LEGACY_KID)).hasSize(1);
        // a NO-kid token (pre-rotation code) falls back to the active key — the same one
        assertThat(encoded(ring.verificationKeys(null))).isEqualTo(KEY_A);
        // any other kid is unknown -> no key -> rejected
        assertThat(ring.verificationKeys("something-else")).isEmpty();
    }

    @Test
    void anExplicitRingSelectsByKidAndFallsBackToActiveForNoKid() {
        JwtKeyRing ring = new JwtKeyRing(ringOf("active", key("active", KEY_A), key("grace", KEY_B)));

        assertThat(ring.activeKid()).isEqualTo("active");
        assertThat(encoded(ring.verificationKeys("active"))).isEqualTo(KEY_A);
        // the grace key still verifies (its window has not closed)
        assertThat(encoded(ring.verificationKeys("grace"))).isEqualTo(KEY_B);
        // no kid -> active key ONLY (not "try every key")
        assertThat(encoded(ring.verificationKeys(null))).isEqualTo(KEY_A);
        // a retired / unknown kid -> empty -> fail-closed
        assertThat(ring.verificationKeys("retired")).isEmpty();
    }

    @Test
    void rejectsAnActiveKidThatIsNotInTheRing() {
        assertThatThrownBy(() -> new JwtKeyRing(ringOf("missing", key("active", KEY_A))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active-kid")
                .hasMessageContaining("not present");
    }

    @Test
    void rejectsAKeysRingWithoutAnActiveKid() {
        assertThatThrownBy(() -> new JwtKeyRing(ringOf(null, key("active", KEY_A))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active-kid must be set");
    }

    @Test
    void rejectsDuplicateKids() {
        assertThatThrownBy(() -> new JwtKeyRing(ringOf("a", key("a", KEY_A), key("a", KEY_B))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate kid");
    }

    @Test
    void rejectsABlankKid() {
        assertThatThrownBy(() -> new JwtKeyRing(ringOf("a", key("a", KEY_A), key("  ", KEY_B))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-blank kid");
    }

    @Test
    void rejectsAnUnresolvedPlaceholderSecret() {
        JwtProperties props = new JwtProperties();
        props.setSecret("${JWT_SECRET}");
        assertThatThrownBy(() -> new JwtKeyRing(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unresolved placeholder");
    }

    @Test
    void rejectsATooShortKey() {
        JwtProperties props = new JwtProperties();
        // 8 bytes -> base64 -> far below the 64-byte HS512 floor
        props.setSecret(Base64.getEncoder().encodeToString(new byte[8]));
        assertThatThrownBy(() -> new JwtKeyRing(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 64 bytes");
    }

    @Test
    void rejectsANonBase64Secret() {
        JwtProperties props = new JwtProperties();
        props.setSecret("this is not base64 !!!");
        assertThatThrownBy(() -> new JwtKeyRing(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid base64");
    }

    @Test
    void aStrongLegacySecretResolvesCleanly() {
        JwtProperties props = new JwtProperties();
        props.setSecret(KEY_A);
        assertThatCode(() -> new JwtKeyRing(props)).doesNotThrowAnyException();
    }

    // --- helpers ---------------------------------------------------------------------------

    private static JwtProperties.Key key(String kid, String secret) {
        JwtProperties.Key key = new JwtProperties.Key();
        key.setKid(kid);
        key.setSecret(secret);
        return key;
    }

    private static JwtProperties ringOf(String activeKid, JwtProperties.Key... keys) {
        JwtProperties props = new JwtProperties();
        props.setActiveKid(activeKid);
        props.setKeys(List.of(keys));
        return props;
    }

    private static String encoded(List<SecretKey> keys) {
        assertThat(keys).hasSize(1);
        return Base64.getEncoder().encodeToString(keys.get(0).getEncoded());
    }
}
