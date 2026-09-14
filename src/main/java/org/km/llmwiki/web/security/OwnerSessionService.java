package org.km.llmwiki.web.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-owned single-user owner session authority (#417 §A).
 *
 * <p>There is deliberately no user, tenant, or organization schema: one
 * configured password hash admits a bounded set of opaque server-side
 * sessions. Sessions live only in memory — a restart clears every session and
 * fails closed back to login. Each session carries an absolute deadline and a
 * sliding idle deadline; logout revokes server-side immediately, and rotation
 * replaces the current token while revoking the presented one.
 *
 * <p>Credential comparison and token comparison both run in constant time.
 * The service never logs, persists, or returns password material.
 */
public class OwnerSessionService {

    private final OwnerSecurityProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public OwnerSessionService(OwnerSecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public OwnerSessionService(OwnerSecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** Result of a successful login: the opaque token plus its lifetimes. */
    public record Login(String token, Duration absoluteTimeout, Duration idleTimeout) {
    }

    /** Validated session view: no secret material, only lifetimes. */
    public record ValidSession(Duration absoluteRemaining, Duration idleRemaining) {
    }

    private record Session(Instant absoluteDeadline, Instant idleDeadline) {
    }

    public boolean passwordMatches(String candidate) {
        if (candidate == null) {
            return false;
        }
        String verifier = properties.passwordVerifier();
        if (verifier != null && !verifier.isBlank()) {
            // Current authority: versioned, salted, adaptive KDF with a
            // constant-time final comparison. Malformed stored verifiers fail
            // closed without distinguishing the failure.
            return OwnerPasswordVerifier.verify(candidate, verifier);
        }
        // Bounded LOCAL_ONLY migration aid: the legacy unsalted SHA-256 hash.
        // Remote ingress never reaches this path (rejected at startup), and the
        // comparison still runs in constant time.
        byte[] expected = hex(properties.passwordHash());
        if (expected == null) {
            return false;
        }
        byte[] actual = sha256(candidate);
        return MessageDigest.isEqual(expected, actual);
    }

    public Login createSession() {
        evictExpired();
        String token = newToken();
        Instant now = clock.instant();
        sessions.put(token, new Session(
                now.plus(properties.sessionAbsoluteTimeout()),
                now.plus(properties.sessionIdleTimeout())));
        while (sessions.size() > properties.maxSessions()) {
            evictOldest();
        }
        return new Login(token, properties.sessionAbsoluteTimeout(), properties.sessionIdleTimeout());
    }

    public Optional<ValidSession> validate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (!now.isBefore(session.absoluteDeadline()) || !now.isBefore(session.idleDeadline())) {
            sessions.remove(token);
            return Optional.empty();
        }
        Session refreshed = new Session(
                session.absoluteDeadline(), now.plus(properties.sessionIdleTimeout()));
        sessions.put(token, refreshed);
        return Optional.of(new ValidSession(
                Duration.between(now, session.absoluteDeadline()),
                properties.sessionIdleTimeout()));
    }

    /** Revokes exactly the presented token; unknown tokens stay a quiet no-op. */
    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            sessions.remove(token);
        }
    }

    /** Rotation revokes the presented token and issues a fresh one. */
    public Optional<Login> rotate(String token) {
        if (validate(token).isEmpty()) {
            return Optional.empty();
        }
        sessions.remove(token);
        return Optional.of(createSession());
    }

    public int activeCount() {
        evictExpired();
        return sessions.size();
    }

    public boolean tokenEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private void evictExpired() {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry ->
                !now.isBefore(entry.getValue().absoluteDeadline())
                        || !now.isBefore(entry.getValue().idleDeadline()));
    }

    private void evictOldest() {
        sessions.entrySet().stream()
                .min(Map.Entry.comparingByValue((left, right) ->
                        left.idleDeadline().compareTo(right.idleDeadline())))
                .map(Map.Entry::getKey)
                .ifPresent(sessions::remove);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static byte[] hex(String value) {
        if (value == null || value.length() != OwnerSecurityProperties.passwordHashHexLength()
                || !value.matches("[0-9a-fA-F]+")) {
            return null;
        }
        byte[] out = new byte[value.length() / 2];
        for (int index = 0; index < out.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            out[index] = (byte) ((high << 4) + low);
        }
        return out;
    }
}
