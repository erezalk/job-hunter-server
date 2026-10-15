package dev.erezalkalay.jobhunter.auth.ratelimit;

import java.time.Duration;
import java.util.Optional;

/**
 * Where rate-limit counters live. The only implementation today,
 * {@link InMemoryRateLimitStore}, keeps them in this JVM: correct for the
 * current single-instance deployment, but every instance of a horizontally
 * scaled deployment would count separately. A shared store (e.g. Redis) is a
 * drop-in replacement: implement this interface atomically per key and
 * register it as the {@code RateLimitStore} bean instead.
 */
public interface RateLimitStore {

    /**
     * Takes one attempt from the bucket {@code key}, governed by {@code policy}.
     *
     * @return empty when the attempt is allowed, otherwise how long until the next one would be
     */
    Optional<Duration> tryConsume(String key, RateLimitPolicy policy);
}
