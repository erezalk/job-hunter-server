package dev.erezalkalay.jobhunter.auth;

import dev.erezalkalay.jobhunter.auth.ratelimit.RateLimitExceededException;
import org.springframework.security.core.AuthenticationException;

/** A login attempt refused by the rate limiter before any credential was checked. */
public class LoginRateLimitedException extends AuthenticationException {

    private final transient RateLimitExceededException limit;

    public LoginRateLimitedException(RateLimitExceededException limit) {
        super(limit.getMessage());
        this.limit = limit;
    }

    public RateLimitExceededException getLimit() {
        return limit;
    }
}
