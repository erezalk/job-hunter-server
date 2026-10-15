package dev.erezalkalay.jobhunter.auth.ratelimit;

import java.time.Duration;

/** Too many attempts; answered as 429 with {@code Retry-After}. The message never depends on the account. */
public class RateLimitExceededException extends RuntimeException {

    public static final String MESSAGE = "Too many attempts. Please wait a few minutes and try again.";

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super(MESSAGE);
        this.retryAfter = retryAfter;
    }

    /** Whole seconds, rounded up, at least 1. */
    public int getRetryAfterSeconds() {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, (retryAfter.toMillis() + 999) / 1000));
    }
}
