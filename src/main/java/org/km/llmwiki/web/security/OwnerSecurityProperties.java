package org.km.llmwiki.web.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Application-owned single-user owner security configuration (#417).
 *
 * <p>Local-only remains the default: {@code auth-enabled=false} keeps every
 * {@code /api/v1} endpoint open exactly as the #393 baseline evaluated
 * (localhost trust). Setting {@code auth-enabled=true} closes {@code /api/v1}
 * behind the owner session authority without changing {@code server.address}
 * (still {@code 127.0.0.1}) and without introducing multi-user, tenant, or
 * organization schema.
 *
 * <p>Binding fails fast when remote protection is requested without a usable
 * credential or with non-positive timeouts/limits.
 */
@ConfigurationProperties("app.owner")
public record OwnerSecurityProperties(
        boolean authEnabled,
        String passwordHash,
        Duration sessionAbsoluteTimeout,
        Duration sessionIdleTimeout,
        int maxSessions,
        boolean cookieSecure,
        String cookieName,
        List<String> allowedHosts,
        List<String> allowedOrigins,
        List<String> trustedProxies,
        boolean trustProxyHeaders,
        int loginMaxAttempts,
        Duration loginWindow,
        int mutationMaxRequests,
        Duration mutationWindow) {

    private static final int PASSWORD_HASH_HEX_LENGTH = 64;

    public OwnerSecurityProperties {
        if (sessionAbsoluteTimeout == null) {
            sessionAbsoluteTimeout = Duration.ofHours(12);
        }
        if (sessionIdleTimeout == null) {
            sessionIdleTimeout = Duration.ofMinutes(30);
        }
        if (maxSessions <= 0) {
            maxSessions = 8;
        }
        if (cookieName == null || cookieName.isBlank()) {
            cookieName = "km-owner-session";
        }
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            allowedHosts = List.of("localhost", "127.0.0.1");
        }
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            allowedOrigins = List.of("http://localhost:8765", "http://127.0.0.1:8765");
        }
        if (trustedProxies == null) {
            trustedProxies = List.of();
        }
        if (loginMaxAttempts <= 0) {
            loginMaxAttempts = 5;
        }
        if (loginWindow == null) {
            loginWindow = Duration.ofMinutes(1);
        }
        if (mutationMaxRequests <= 0) {
            mutationMaxRequests = 60;
        }
        if (mutationWindow == null) {
            mutationWindow = Duration.ofMinutes(1);
        }
        allowedHosts = List.copyOf(allowedHosts.stream()
                .map(String::strip).filter(value -> !value.isEmpty()).toList());
        allowedOrigins = List.copyOf(allowedOrigins.stream()
                .map(String::strip).filter(value -> !value.isEmpty()).toList());
        trustedProxies = List.copyOf(trustedProxies.stream()
                .map(String::strip).filter(value -> !value.isEmpty()).toList());
    }

    /** Fail-fast validation invoked at startup when the boundary is constructed. */
    public void validate() {
        if (!authEnabled) {
            return;
        }
        if (passwordHash == null || !passwordHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException(
                    "Owner authentication is enabled but no valid 64-hex password hash is configured");
        }
        // #422 §B: proxy locator headers are never trusted without an explicit peer
        // allowlist, so a remote origin cannot be forged through Forwarded material.
        if (trustProxyHeaders && trustedProxies.isEmpty()) {
            throw new IllegalStateException(
                    "Owner proxy-header trust requires explicit trusted proxies");
        }
        if (sessionAbsoluteTimeout.isZero() || sessionAbsoluteTimeout.isNegative()
                || sessionIdleTimeout.isZero() || sessionIdleTimeout.isNegative()) {
            throw new IllegalStateException("Owner session timeouts must be positive");
        }
        if (sessionIdleTimeout.compareTo(sessionAbsoluteTimeout) > 0) {
            throw new IllegalStateException("Owner idle timeout must not exceed absolute timeout");
        }
        if (allowedHosts.isEmpty() || allowedOrigins.isEmpty()) {
            throw new IllegalStateException("Owner allowlists must not be empty when auth is enabled");
        }
        if (loginWindow.isZero() || loginWindow.isNegative()
                || mutationWindow.isZero() || mutationWindow.isNegative()) {
            throw new IllegalStateException("Owner rate-limit windows must be positive");
        }
    }

    public static int passwordHashHexLength() {
        return PASSWORD_HASH_HEX_LENGTH;
    }
}
