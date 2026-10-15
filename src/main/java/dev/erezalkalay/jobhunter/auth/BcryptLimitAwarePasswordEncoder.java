package dev.erezalkalay.jobhunter.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The application's BCrypt encoder (same algorithm, strength and hash format
 * as before). BCrypt rejects input over 72 bytes with an exception; a
 * submitted password that long can never be correct, so matching answers
 * false instead of failing the request with a 500. Encoding still rejects it
 * ({@link PasswordPolicy} prevents that from ever being reached).
 */
public class BcryptLimitAwarePasswordEncoder extends BCryptPasswordEncoder {

    @Override
    protected boolean matchesNonNull(String rawPassword, String encodedPassword) {
        if (PasswordPolicy.exceedsMaxBytes(rawPassword)) {
            return false;
        }
        return super.matchesNonNull(rawPassword, encodedPassword);
    }
}
