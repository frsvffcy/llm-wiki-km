package org.km.llmwiki.web.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class OwnerSessionServiceTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String PASSWORD_HASH = sha256Hex(PASSWORD);
    private static final String WRONG_HASH = sha256Hex("something else entirely");

    static String sha256Hex(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte part : bytes) {
                hex.append(String.format("%02x", part));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

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

    private OwnerSessionService service(MutableClock clock) {
        OwnerSecurityProperties properties = new OwnerSecurityProperties(true, PASSWORD_HASH,
                Duration.ofHours(12), Duration.ofMinutes(30), 8, true, "km-owner-session",
                List.of("localhost"), List.of("http://localhost:8765"), List.of(), false,
                5, Duration.ofMinutes(1), 60, Duration.ofMinutes(1));
        return new OwnerSessionService(properties, clock);
    }

    @Test
    void passwordVerificationAcceptsOnlyTheConfiguredSecret() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T00:00:00Z"));
        OwnerSessionService sessions = service(clock);

        assertThat(sessions.passwordMatches(PASSWORD)).isTrue();
        assertThat(sessions.passwordMatches("wrong")).isFalse();
        assertThat(sessions.passwordMatches(null)).isFalse();
        assertThat(sessions.passwordMatches("")).isFalse();
    }

    @Test
    void sessionLifecycleValidatesThenRevokesOnLogout() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T00:00:00Z"));
        OwnerSessionService sessions = service(clock);

        OwnerSessionService.Login login = sessions.createSession();
        assertThat(login.token()).isNotBlank();
        assertThat(sessions.validate(login.token())).isPresent();

        sessions.revoke(login.token());
        assertThat(sessions.validate(login.token())).isEmpty();
    }

    @Test
    void unknownBlankAndNullTokensFailClosed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T00:00:00Z"));
        OwnerSessionService sessions = service(clock);

        assertThat(sessions.validate("not-a-session")).isEmpty();
        assertThat(sessions.validate("")).isEmpty();
        assertThat(sessions.validate(null)).isEmpty();
    }

    @Test
    void absoluteExpiryFailsClosedEvenWithActivity() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T00:00:00Z"));
        OwnerSessionService sessions = service(clock);
        OwnerSessionService.Login login = sessions.createSession();

        for (int step = 0; step < 33; step++) {
            clock.advance(Duration.ofMinutes(20));
            assertThat(sessions.validate(login.token())).isPresent();
        }
        // Now 11:00 with a freshly refreshed idle deadline (11:30), while the
        // absolute deadline stays pinned at 12:00: crossing it fails closed
        // even though the idle budget is still unspent.
        clock.advance(Duration.ofMinutes(20));
        assertThat(sessions.validate(login.token())).isPresent();
        clock.advance(Duration.ofMinutes(20));
        assertThat(sessions.validate(login.token())).isPresent();
        clock.advance(Duration.ofMinutes(20));
        assertThat(sessions.validate(login.token())).isEmpty();
    }

    @Test
    void idleExpiryFailsClosedWithoutActivity() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T00:00:00Z"));
        OwnerSessionService sessions = service(clock);
        OwnerSessionService.Login login = sessions.createSession();

        clock.advance(Duration.ofMinutes(30).plusSeconds(1));
        assertThat(sessions.validate(login.token())).isEmpty();
    }

    @Test
    void activityRefreshesIdleDeadlineButNotAbsoluteDeadline() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T00:00:00Z"));
        OwnerSessionService sessions = service(clock);
        OwnerSessionService.Login login = sessions.createSession();

        clock.advance(Duration.ofMinutes(20));
        assertThat(sessions.validate(login.token())).isPresent();
        clock.advance(Duration.ofMinutes(20));
        assertThat(sessions.validate(login.token())).isPresent();
        clock.advance(Duration.ofHours(12));
        assertThat(sessions.validate(login.token())).isEmpty();
    }

    @Test
    void rotationIssuesFreshTokenAndRevokesThePresentedOne() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T00:00:00Z"));
        OwnerSessionService sessions = service(clock);
        OwnerSessionService.Login login = sessions.createSession();

        var rotated = sessions.rotate(login.token());
        assertThat(rotated).isPresent();
        assertThat(rotated.get().token()).isNotEqualTo(login.token());
        assertThat(sessions.validate(login.token())).isEmpty();
        assertThat(sessions.validate(rotated.get().token())).isPresent();
    }

    @Test
    void rotationOfUnknownTokenFailsClosedWithoutMinting() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T00:00:00Z"));
        OwnerSessionService sessions = service(clock);

        assertThat(sessions.rotate("unknown")).isEmpty();
        assertThat(sessions.activeCount()).isZero();
    }

    @Test
    void restartClearsEverySessionAndFailsClosed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T00:00:00Z"));
        OwnerSessionService before = service(clock);
        OwnerSessionService.Login login = before.createSession();
        assertThat(before.validate(login.token())).isPresent();

        OwnerSessionService restarted = service(clock);
        assertThat(restarted.validate(login.token())).isEmpty();
    }

    @Test
    void mintedTokensAreOpaqueAndUnique() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T00:00:00Z"));
        OwnerSessionService sessions = service(clock);

        OwnerSessionService.Login first = sessions.createSession();
        OwnerSessionService.Login second = sessions.createSession();
        assertThat(first.token()).isNotEqualTo(second.token());
        assertThat(first.token()).doesNotContain(PASSWORD);
        assertThat(second.token()).hasSizeGreaterThanOrEqualTo(43);
    }

    @Test
    void sessionBoundIsEnforcedByEvictingTheOldest() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T00:00:00Z"));
        OwnerSecurityProperties properties = new OwnerSecurityProperties(true, WRONG_HASH,
                Duration.ofHours(12), Duration.ofMinutes(30), 2, true, "km-owner-session",
                List.of("localhost"), List.of("http://localhost:8765"), List.of(), false,
                5, Duration.ofMinutes(1), 60, Duration.ofMinutes(1));
        OwnerSessionService sessions = new OwnerSessionService(properties, clock);

        OwnerSessionService.Login first = sessions.createSession();
        clock.advance(Duration.ofSeconds(1));
        sessions.createSession();
        clock.advance(Duration.ofSeconds(1));
        sessions.createSession();
        assertThat(sessions.activeCount()).isEqualTo(2);
        assertThat(sessions.validate(first.token())).isEmpty();
    }
}
