package dev.erezalkalay.jobhunter.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The per-email bucket key must not be weaker than the database's own email comparison (upper() on both sides). */
class AuthRateLimiterKeyTest {

    @Test
    void spellingsThatResolveToTheSameAccountShareABucket() {
        String key = AuthRateLimiter.emailKey("admin@example.com");
        assertThat(AuthRateLimiter.emailKey("  ADMIN@Example.COM\t")).isEqualTo(key);
        // PostgreSQL: upper('admın') = upper('admin'), upper('ſ') = 'S'.
        assertThat(AuthRateLimiter.emailKey("admın@example.com")).isEqualTo(key);
        assertThat(AuthRateLimiter.emailKey("ſam@example.com")).isEqualTo(AuthRateLimiter.emailKey("sam@example.com"));
        // PostgreSQL: upper('ß') = 'ẞ' = upper('ẞ').
        assertThat(AuthRateLimiter.emailKey("ß@example.com")).isEqualTo(AuthRateLimiter.emailKey("ẞ@example.com"));
        // Kelvin sign vs. k.
        assertThat(AuthRateLimiter.emailKey("Kate@example.com")).isEqualTo(AuthRateLimiter.emailKey("kate@example.com"));
    }

    @Test
    void differentAddressesStayApart() {
        assertThat(AuthRateLimiter.emailKey("a.b@example.com")).isNotEqualTo(AuthRateLimiter.emailKey("ab@example.com"));
        assertThat(AuthRateLimiter.emailKey("a+x@example.com")).isNotEqualTo(AuthRateLimiter.emailKey("a@example.com"));
    }

    @Test
    void overlongInputIsNeverStoredAsAKey() {
        assertThat(AuthRateLimiter.emailKey("x".repeat(2_000_000))).isEqualTo("<overlong>");
        assertThat(AuthRateLimiter.emailKey(null)).isEmpty();
    }
}
