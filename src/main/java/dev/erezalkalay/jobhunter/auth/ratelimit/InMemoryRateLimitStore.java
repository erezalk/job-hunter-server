package dev.erezalkalay.jobhunter.auth.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Token buckets in a {@link ConcurrentHashMap}, one entry per key, updated
 * atomically with {@code compute}. Per-JVM only (see {@link RateLimitStore}):
 * counters reset on restart and are not shared between instances.
 *
 * <p>Each bucket is stored as the generic cell rate algorithm's single
 * "theoretical arrival time" (exact integer time arithmetic, no fractional
 * tokens): every allowed attempt pushes it one interval
 * ({@code refillPeriod / capacity}) further, and an attempt is allowed while
 * it is at most {@code capacity - 1} intervals in the future. Refused
 * attempts change nothing, so retrying does not extend the wait.
 *
 * <p>Memory stays bounded by recent traffic: a bucket whose arrival time has
 * passed is full and carries no information, so once the map grows past
 * {@link #PRUNE_THRESHOLD} entries such buckets are dropped.
 */
@Component
public class InMemoryRateLimitStore implements RateLimitStore {

    static final int PRUNE_THRESHOLD = 10_000;

    private final ConcurrentMap<String, Instant> arrivalTimes = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public InMemoryRateLimitStore() {
        this(Clock.systemUTC());
    }

    InMemoryRateLimitStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<Duration> tryConsume(String key, RateLimitPolicy policy) {
        Instant now = clock.instant();
        Duration interval = policy.refillPeriod().dividedBy(policy.capacity());
        Duration burst = interval.multipliedBy(policy.capacity() - 1L);
        Duration[] wait = new Duration[1];
        arrivalTimes.compute(key, (k, stored) -> {
            Instant arrival = stored == null || stored.isBefore(now) ? now : stored;
            Duration ahead = Duration.between(now, arrival);
            if (ahead.compareTo(burst) > 0) {
                wait[0] = ahead.minus(burst);
                return stored;
            }
            return arrival.plus(interval);
        });
        if (arrivalTimes.size() > PRUNE_THRESHOLD) {
            prune(now);
        }
        return Optional.ofNullable(wait[0]);
    }

    /** Removes every bucket that has refilled completely (forgetting it changes nothing). */
    void prune(Instant now) {
        arrivalTimes.values().removeIf(arrival -> !arrival.isAfter(now));
    }

    int size() {
        return arrivalTimes.size();
    }

    /** Forgets every counter (tests only; production never needs it). */
    public void clear() {
        arrivalTimes.clear();
    }
}
