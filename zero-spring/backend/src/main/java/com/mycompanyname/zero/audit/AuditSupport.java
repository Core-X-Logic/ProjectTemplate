package com.mycompanyname.zero.audit;

import java.time.temporal.Temporal;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Stateless helpers shared across the audit module: value truncation to column sizes, sensitive
 * name detection (for masking secrets in audit output), and simple-value classification used by the
 * entity change listener to skip associations/collections.
 */
public final class AuditSupport {

    private AuditSupport() {
    }

    public static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * True for property/parameter names whose values must never be persisted in clear text
     * (passwords, hashes, tokens, confirmation codes, ...).
     */
    public static boolean isSensitive(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("password")
                || n.contains("secret")
                || n.contains("token")
                || n.contains("credential")
                || n.endsWith("hash")
                || n.equals("emailconfirmationcode")
                || n.equals("otp")
                || n.equals("pin");
    }

    /**
     * True for scalar values worth recording as property changes. Associations, collections and
     * binary blobs return false so they are skipped.
     */
    public static boolean isSimpleValue(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Temporal
                || value instanceof Date
                || value instanceof Enum<?>
                || value instanceof UUID;
    }

    public static String formatValue(Object value) {
        if (value == null) {
            return null;
        }
        return truncate(String.valueOf(value), 2000);
    }
}
