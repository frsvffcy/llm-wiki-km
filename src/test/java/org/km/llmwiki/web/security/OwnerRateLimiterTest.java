package org.km.llmwiki.web.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class OwnerRateLimiterTest {

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
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
            return now.get();
        }
    }

    @Test
    void allowsWithinBoundAndRejectsBeyondIt() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T00:00:00Z"));
        OwnerRateLimiter limiter = new OwnerRateLimiter(clock);

        assertThat(limiter.tryAcquire(OwnerRateLimiter.Bucket.LOGIN, "203.0.113.9",
                5, Duration.ofMinutes(1))).isTrue();
        for (int attempt = 1; attempt < 5; attempt++) {
            assertThat(limiter.tryAcquire(OwnerRateLimiter.Bucket.LOGIN, "203.0.113.9",
                    5, Duration.ofMinutes(1))).isTrue();
        }
        assertThat(limiter.tryAcquire(OwnerRateLimiter.Bucket.LOGIN, "203.0.113.9",
                5, Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void windowSlideAdmitsAgainAfterExpiry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T00:00:00Z"));
        OwnerRateLimiter limiter = new OwnerRateLimiter(clock);

        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.tryAcquire(OwnerRateLimiter.Bucket.LOGIN, "203.0.113.9",
                    5, Duration.ofMinutes(1));
        }
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertThat(limiter.tryAcquire(OwnerRateLimiter.Bucket.LOGIN, "203.0.113.9",
                5, Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void keysAndBucketsAreIsolated() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T00:00:00Z"));
        OwnerRateLimiter limiter = new OwnerRateLimiter(clock);

        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.tryAcquire(OwnerRateLimiter.Bucket.LOGIN, "203.0.113.9",
                    5, Duration.ofMinutes(1));
        }
        assertThat(limiter.tryAcquire(OwnerRateLimiter.Bucket.LOGIN, "203.0.113.10",
                5, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire(OwnerRateLimiter.Bucket.MUTATION, "203.0.113.9",
                5, Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void trackedStateStaysBounded() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T00:00:00Z"));
        OwnerRateLimiter limiter = new OwnerRateLimiter(clock, 4);

        for (int index = 0; index < 4; index++) {
            assertThat(limiter.tryAcquire(OwnerRateLimiter.Bucket.LOGIN, "client-" + index,
                    5, Duration.ofMinutes(1))).isTrue();
        }
        assertThat(limiter.tryAcquire(OwnerRateLimiter.Bucket.LOGIN, "client-overflow",
                5, Duration.ofMinutes(1))).isFalse();
        assertThat(limiter.trackedKeys()).isEqualTo(4);
    }

    @Test
    void invalidRequestsFailClosed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T00:00:00Z"));
        OwnerRateLimiter limiter = new OwnerRateLimiter(clock);

        assertThat(limiter.tryAcquire(OwnerRateLimiter.Bucket.LOGIN, null,
                5, Duration.ofMinutes(1))).isFalse();
        assertThat(limiter.tryAcquire(OwnerRateLimiter.Bucket.LOGIN, " ",
                5, Duration.ofMinutes(1))).isFalse();
        assertThat(limiter.tryAcquire(OwnerRateLimiter.Bucket.LOGIN, "client",
                0, Duration.ofMinutes(1))).isFalse();
    }
}
