package org.km.llmwiki.system;

import org.km.llmwiki.web.security.HostOriginPolicy;

import java.net.URI;
import java.util.Locale;

/**
 * Canonical external browser-origin contract for private ingress (#422 §B).
 *
 * <p>A single application-owned truth describing how a remote Browser reaches the
 * bounded private ingress: scheme, host, and port. Host validation and Origin
 * validation both resolve against this truth (via {@link HostOriginPolicy} as the
 * shared canonicalizer) so {@code forwarder-binds}, {@code allowed-hosts}, and
 * {@code allowed-origins} cannot silently drift apart.
 *
 * <p>Rules: {@code http}/{@code https} only; no userinfo, path (beyond {@code /}),
 * query, or fragment; no wildcard bind shapes; explicit effective port
 * ({@code 80}/{@code 443} defaults when absent). Loopback and wildcard rejection
 * for {@code PRIVATE_INGRESS} is enforced by the deployment validator, not here,
 * so this parser stays a pure syntax authority reusable by every mode.
 */
public final class BrowserOriginPolicy {

    private BrowserOriginPolicy() {
    }

    /** Parsed browser origin with its effective port and canonical comparison form. */
    public record BrowserOrigin(String scheme, String host, int port, String canonical) {
    }

    /**
     * Parses a configured browser origin or throws with a fixed operator-safe message.
     * The message never echoes the supplied value.
     */
    public static BrowserOrigin parse(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(
                    "Deployment profile is invalid: the browser origin is malformed");
        }
        String canonical = HostOriginPolicy.canonicalOrigin(value.strip());
        if (canonical == null) {
            throw new IllegalArgumentException(
                    "Deployment profile is invalid: the browser origin is malformed");
        }
        URI origin = URI.create(canonical);
        String host = origin.getHost();
        if (host == null || host.isEmpty() || isWildcardHost(host)) {
            throw new IllegalArgumentException(
                    "Deployment profile is invalid: the browser origin is malformed");
        }
        String scheme = origin.getScheme().toLowerCase(Locale.ROOT);
        int port = origin.getPort() == -1
                ? ("https".equals(scheme) ? 443 : 80)
                : origin.getPort();
        return new BrowserOrigin(scheme, host.toLowerCase(Locale.ROOT), port, canonical);
    }

    /** Loopback shapes that can never describe a true remote ingress. */
    public static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost") || normalized.equals("::1")) {
            return true;
        }
        return normalized.matches("127(\\.\\d{1,3}){3}");
    }

    /** Numeric address literals (IPv4 dotted-decimal or IPv6 with a colon). */
    public static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.contains(":")) {
            return true;
        }
        if (!normalized.matches("(\\d{1,3}\\.){3}\\d{1,3}")) {
            return false;
        }
        for (String octet : normalized.split("\\.", -1)) {
            int value;
            try {
                value = Integer.parseInt(octet);
            } catch (NumberFormatException malformed) {
                return false;
            }
            if (value < 0 || value > 255) {
                return false;
            }
        }
        return true;
    }

    static boolean isWildcardHost(String host) {
        String normalized = host.strip().toLowerCase(Locale.ROOT);
        return normalized.isEmpty()
                || normalized.equals("*")
                || normalized.equals("0.0.0.0")
                || normalized.equals("::")
                || normalized.equals("[::]")
                || normalized.equals("0:0:0:0:0:0:0:0");
    }
}
