package dev.erezalkalay.jobhunter.auth;

import java.util.regex.Pattern;

/**
 * Account email rules. The only normalization is removing surrounding
 * whitespace; the address is otherwise stored as entered (case kept, dots and
 * "+tags" untouched, no provider-specific rules). Identity is case-insensitive:
 * {@code users.email} is unique on {@code upper(email)}
 * ({@code uq_users_email_ignore_case}) and every lookup compares the same way.
 */
public final class EmailAddresses {

    /** RFC 5321 path limit; the column holds 255. */
    public static final int MAX_LENGTH = 254;

    private static final int MAX_LOCAL_PART_LENGTH = 64;

    // A pragmatic format check, not full RFC 5322: one "@", a local part without
    // whitespace/control/specials, and a dotted ASCII domain (IDNs as punycode).
    private static final Pattern FORMAT = Pattern.compile(
            "[^\\s\\p{Cntrl}@<>()\\[\\]\\\\,;:\"]+"
                    + "@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    private EmailAddresses() {
    }

    /** Surrounding whitespace removed; null stays null. */
    public static String normalize(String email) {
        return email == null ? null : email.strip();
    }

    /** The problem with an already normalized address, or null when it is acceptable. */
    public static String problem(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return "Email is required";
        }
        if (normalized.length() > MAX_LENGTH) {
            return "Email must be at most " + MAX_LENGTH + " characters";
        }
        int at = normalized.lastIndexOf('@');
        if (!FORMAT.matcher(normalized).matches() || at > MAX_LOCAL_PART_LENGTH) {
            return "Enter a valid email address";
        }
        return null;
    }
}
