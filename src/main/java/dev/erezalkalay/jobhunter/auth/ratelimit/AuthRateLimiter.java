package dev.erezalkalay.jobhunter.auth.ratelimit;

import dev.erezalkalay.jobhunter.auth.EmailAddresses;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Rate limits for the credential-handling endpoints. Every check counts the
 * attempt itself, before any account is looked up, so the outcome (allowed or
 * 429) is identical whether or not an account exists.
 *
 * <ul>
 *   <li>Login: per client IP (slows spraying many accounts) and per email
 *       (slows guessing one account's password from many IPs). The per-email
 *       limit means someone can delay a specific user's logins, at most for
 *       one refill interval after they stop; it never locks an account.</li>
 *   <li>Sign-up: per client IP.</li>
 *   <li>Change password: per user (a hijacked session cannot brute-force the
 *       current password).</li>
 * </ul>
 *
 * The client IP is the servlet remote address. Behind a reverse proxy that is
 * the proxy's address unless {@code server.forward-headers-strategy} is set
 * for a trusted proxy; X-Forwarded-For is never read directly (spoofable).
 */
@Component
@EnableConfigurationProperties(AuthRateLimitProperties.class)
public class AuthRateLimiter {

    private final RateLimitStore store;
    private final AuthRateLimitProperties properties;

    public AuthRateLimiter(RateLimitStore store, AuthRateLimitProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    public void checkLogin(String clientIp, String email) {
        consume("login:ip:" + clientIp, properties.loginPerIp());
        consume("login:email:" + emailKey(email), properties.loginPerEmail());
    }

    /**
     * Every spelling that resolves to the same account must share one bucket.
     * Accounts are matched with PostgreSQL's upper() on both sides, which also
     * equates e.g. "ı"/"i", "ſ"/"s", "ß"/"ẞ"; folding each code point to upper
     * and back to lower case covers those. Addresses longer than any valid one
     * cannot belong to an account and share a single bucket, so the map never
     * holds arbitrarily large keys.
     */
    static String emailKey(String email) {
        String stripped = email == null ? "" : email.strip();
        if (stripped.length() > EmailAddresses.MAX_LENGTH) {
            return "<overlong>";
        }
        StringBuilder key = new StringBuilder(stripped.length());
        stripped.codePoints().forEach(c -> key.appendCodePoint(Character.toLowerCase(Character.toUpperCase(c))));
        return key.toString();
    }

    public void checkSignup(String clientIp) {
        consume("signup:ip:" + clientIp, properties.signupPerIp());
    }

    public void checkPasswordChange(long userId) {
        consume("password-change:user:" + userId, properties.passwordChangePerUser());
    }

    private void consume(String key, RateLimitPolicy policy) {
        store.tryConsume(key, policy).ifPresent(wait -> {
            throw new RateLimitExceededException(wait);
        });
    }
}
