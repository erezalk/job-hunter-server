package dev.erezalkalay.jobhunter.auth.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code app.auth.rate-limit.*}; defaults in application.yml. */
@ConfigurationProperties("app.auth.rate-limit")
public record AuthRateLimitProperties(
        RateLimitPolicy loginPerIp,
        RateLimitPolicy loginPerEmail,
        RateLimitPolicy signupPerIp,
        RateLimitPolicy passwordChangePerUser
) {
}
