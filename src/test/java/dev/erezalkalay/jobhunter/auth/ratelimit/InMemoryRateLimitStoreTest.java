package dev.erezalkalay.jobhunter.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Deterministic: time only moves when the test moves it. */
class InMemoryRateLimitStoreTest {

    private static final RateLimitPolicy FIVE_PER_MINUTE = new RateLimitPolicy(5, Duration.ofMinutes(1));

    private final MutableClock clock = new MutableClock();
    private final InMemoryRateLimitStore store = new InMemoryRateLimitStore(clock);

    @Test
    void allowsTheBurstThenRefusesWithTheWaitUntilTheNextAttempt() {
        for (int i = 0; i < 5; i++) {
            assertThat(store.tryConsume("k", FIVE_PER_MINUTE)).as("attempt " + i).isEmpty();
        }
        assertThat(store.tryConsume("k", FIVE_PER_MINUTE)).contains(Duration.ofSeconds(12));

        clock.advance(Duration.ofSeconds(5));
        assertThat(store.tryConsume("k", FIVE_PER_MINUTE)).contains(Duration.ofSeconds(7));
    }

    @Test
    void refillsContinuouslySoNobodyIsLockedOutForLong() {
        drain("k");

        clock.advance(Duration.ofSeconds(12));
        assertThat(store.tryConsume("k", FIVE_PER_MINUTE)).isEmpty();
        assertThat(store.tryConsume("k", FIVE_PER_MINUTE)).isPresent();

        clock.advance(Duration.ofHours(1));
        for (int i = 0; i < 5; i++) {
            assertThat(store.tryConsume("k", FIVE_PER_MINUTE)).isEmpty();
        }
        assertThat(store.tryConsume("k", FIVE_PER_MINUTE)).isPresent();
    }

    @Test
    void refusedAttemptsDoNotExtendTheWait() {
        drain("k");
        for (int i = 0; i < 100; i++) {
            assertThat(store.tryConsume("k", FIVE_PER_MINUTE)).isPresent();
        }
        clock.advance(Duration.ofSeconds(12));
        assertThat(store.tryConsume("k", FIVE_PER_MINUTE)).isEmpty();
    }

    @Test
    void keysAreIndependent() {
        drain("a");
        assertThat(store.tryConsume("b", FIVE_PER_MINUTE)).isEmpty();
        assertThat(store.tryConsume("a", FIVE_PER_MINUTE)).isPresent();
    }

    @Test
    void pruningForgetsOnlyBucketsThatHaveFullyRefilled() {
        drain("busy");
        store.tryConsume("idle", FIVE_PER_MINUTE);
        clock.advance(Duration.ofSeconds(30));
        drain("recent");

        store.prune(clock.instant());
        assertThat(store.size()).isEqualTo(2); // "busy" (half refilled) and "recent" remain

        clock.advance(Duration.ofMinutes(2));
        store.prune(clock.instant());
        assertThat(store.size()).isZero();
    }

    @Test
    void manyDistinctKeysAreBoundedByPruning() {
        for (int i = 0; i < InMemoryRateLimitStore.PRUNE_THRESHOLD + 50; i++) {
            store.tryConsume("key-" + i, FIVE_PER_MINUTE);
            clock.advance(Duration.ofMillis(10));
        }
        // Every bucket refills 1 attempt every 12 s; older keys were full and dropped.
        assertThat(store.size()).isLessThan(InMemoryRateLimitStore.PRUNE_THRESHOLD + 50);
    }

    @Test
    void invalidPoliciesAreRejected() {
        assertThatThrownBy(() -> new RateLimitPolicy(0, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitPolicy(1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retryAfterIsWholeSecondsRoundedUp() {
        assertThat(new RateLimitExceededException(Duration.ofMillis(1)).getRetryAfterSeconds()).isEqualTo(1);
        assertThat(new RateLimitExceededException(Duration.ofMillis(12_001)).getRetryAfterSeconds()).isEqualTo(13);
    }

    private void drain(String key) {
        Optional<Duration> result;
        do {
            result = store.tryConsume(key, FIVE_PER_MINUTE);
        } while (result.isEmpty());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
