package org.km.llmwiki.web.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded in-memory sliding-window rate limiter for remote-facing abuse control
 * (#417 §F).
 *
 * <p>This is request-rate and concurrency policy. It is intentionally separate
 * from the existing per-request size bounds (multipart limits, bounded
 * extraction ceilings): those cap a single request, while this limiter caps
 * how often a client may retry high-risk operations such as login,
 * provider-triggering Ask calls, uploads, rebuild/repair, and proposal
 * mutations.
 *
 * <p>State is bounded: at most {@code maxKeys} keys are retained and each key
 * keeps only timestamps inside its longest configured window.
 */
public class OwnerRateLimiter {

    public enum Bucket {
        LOGIN,
        MUTATION
    }

    private final Clock clock;
    private final int maxKeys;
    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

    public OwnerRateLimiter(Clock clock, int maxKeys) {
        this.clock = clock;
        this.maxKeys = maxKeys;
    }

    public OwnerRateLimiter(Clock clock) {
        this(clock, 2048);
    }

    public boolean tryAcquire(Bucket bucket, String key, int maxAttempts, Duration window) {
        if (key == null || key.isBlank() || maxAttempts <= 0
                || window.isZero() || window.isNegative()) {
            return false;
        }
        String namespaced = bucket.name() + "|" + key;
        if (attempts.size() >= maxKeys && !attempts.containsKey(namespaced)) {
            return false;
        }
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);
        Deque<Instant> timestamps = attempts.computeIfAbsent(namespaced, ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && !timestamps.peekFirst().isAfter(cutoff)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxAttempts) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    public void clear() {
        attempts.clear();
    }

    public int trackedKeys() {
        return attempts.size();
    }
}
