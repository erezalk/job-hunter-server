package dev.erezalkalay.jobhunter.auth;

import java.nio.charset.StandardCharsets;

/**
 * The one password rule, for sign-up and change password alike: 8 characters
 * or more, at most 72 bytes in UTF-8 (BCrypt only accepts 72 bytes; that is 72
 * plain ASCII characters, fewer with accented or non-Latin ones), and not only
 * whitespace. No composition rules. The password is never trimmed or changed.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** The problem with {@code password}, or null when it is acceptable. */
    public static String problem(String password) {
        if (password == null || password.isEmpty()) {
            return "Password is required";
        }
        if (password.codePointCount(0, password.length()) < MIN_LENGTH) {
            return "Password must be at least " + MIN_LENGTH + " characters";
        }
        if (exceedsMaxBytes(password)) {
            return "Password is too long: use at most " + MAX_BYTES
                    + " characters (accented or non-Latin characters count as 2-4)";
        }
        if (password.isBlank()) {
            return "Password must not consist only of spaces";
        }
        return null;
    }

    public static boolean exceedsMaxBytes(String password) {
        // Cheap bound first, so a huge request is not encoded in full.
        return password.length() > MAX_BYTES || password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
    }
}
