package dev.erezalkalay.jobhunter.auth.ratelimit;

import java.time.Duration;

/**
 * A token bucket: up to {@code capacity} attempts in a burst, refilled
 * continuously at {@code capacity} per {@code refillPeriod}. Nobody is ever
 * locked out for longer than one refill interval ({@code refillPeriod / capacity})
 * after the burst is used up.
 */
public record RateLimitPolicy(int capacity, Duration refillPeriod) {

    public RateLimitPolicy {
        if (capacity < 1) {
            throw new IllegalArgumentException("Rate limit capacity must be at least 1");
        }
        if (refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("Rate limit refill period must be positive");
        }
    }
}
