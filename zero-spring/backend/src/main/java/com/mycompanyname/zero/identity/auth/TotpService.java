package com.mycompanyname.zero.identity.auth;

import com.mycompanyname.zero.config.TwoFactorProperties;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.springframework.stereotype.Service;

/**
 * RFC 6238 TOTP mechanics: secret generation, the {@code otpauth://} provisioning URI, and code
 * verification with an explicit time-step tolerance. Pure — no database, no user state. The stored
 * secret is handed in by the caller (decrypted from {@code users.two_factor_secret} first).
 *
 * <p>Wraps {@code dev.samstevens.totp}. The verification window is
 * {@link TwoFactorProperties#getTotpWindow()} steps either side of "now": with the default of 1 and a
 * 30-second step, a code minted in the previous, current, or next window is accepted, which absorbs
 * clock skew and the human who submits a code as it rolls over. The window is kept small on purpose —
 * every extra step is an extra valid code an attacker could be guessing.
 */
@Service
public class TotpService {

    private static final HashingAlgorithm ALGORITHM = HashingAlgorithm.SHA1;
    private static final int DIGITS = 6;

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final CodeVerifier codeVerifier;
    private final int periodSeconds;

    public TotpService(TwoFactorProperties properties) {
        this.periodSeconds = properties.getTotpTimeStepSeconds();
        DefaultCodeVerifier verifier =
                new DefaultCodeVerifier(new DefaultCodeGenerator(ALGORITHM, DIGITS), new SystemTimeProvider());
        verifier.setTimePeriod(periodSeconds);
        verifier.setAllowedTimePeriodDiscrepancy(properties.getTotpWindow());
        this.codeVerifier = verifier;
    }

    /** A fresh base32 TOTP shared secret (what {@code enable} confirms and the URI carries). */
    public String generateSecret() {
        return secretGenerator.generate();
    }

    /**
     * The {@code otpauth://totp/...} URI an authenticator app imports (typically via QR). {@code label}
     * is the account shown to the user; {@code issuer} is the app/tenant name.
     */
    public String provisioningUri(String secret, String label, String issuer) {
        QrData data = new QrData.Builder()
                .label(label)
                .secret(secret)
                .issuer(issuer)
                .algorithm(ALGORITHM)
                .digits(DIGITS)
                .period(periodSeconds)
                .build();
        return data.getUri();
    }

    /**
     * True iff {@code code} is a currently-valid TOTP for {@code secret} within the configured window.
     * Returns false for a malformed, empty or wrong code rather than throwing — the caller decides
     * what a false means (a wrong code decrements the challenge; it is never an oracle).
     */
    public boolean verify(String secret, String code) {
        if (secret == null || code == null) {
            return false;
        }
        return codeVerifier.isValidCode(secret, code.trim());
    }
}
