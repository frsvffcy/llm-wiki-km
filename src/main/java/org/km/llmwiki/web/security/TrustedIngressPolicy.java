package org.km.llmwiki.web.security;

import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Trusted-ingress decision for proxy headers (#417 §D).
 *
 * <p>The application never trusts client-supplied identity material:
 * {@code Forwarded}, {@code X-Forwarded-User}, {@code X-Remote-User} and
 * equivalent upstream identity headers are always ignored — they can never
 * establish owner identity. Only the application-owned session counts.
 *
 * <p>Network locator headers ({@code X-Forwarded-Host},
 * {@code X-Forwarded-Proto}) are likewise ignored unless the direct peer
 * address is an explicitly configured trusted proxy <em>and</em> proxy-header
 * trust is enabled. A direct backend request therefore cannot forge trusted
 * ingress metadata.
 */
public final class TrustedIngressPolicy {

    private TrustedIngressPolicy() {
    }

    public static boolean trustedPeer(String remoteAddr, List<String> trustedProxies) {
        if (remoteAddr == null || trustedProxies == null || trustedProxies.isEmpty()) {
            return false;
        }
        String normalized = remoteAddr.strip();
        return trustedProxies.stream().anyMatch(candidate -> candidate.strip().equals(normalized));
    }

    public static boolean useProxyHeaders(
            HttpServletRequest request, OwnerSecurityProperties properties) {
        return properties.trustProxyHeaders()
                && trustedPeer(request.getRemoteAddr(), properties.trustedProxies());
    }

    /** Effective host for allowlist validation: direct unless trusted ingress overrides. */
    public static String effectiveHost(HttpServletRequest request, OwnerSecurityProperties properties) {
        if (useProxyHeaders(request, properties)) {
            String forwarded = singleHeader(request, "X-Forwarded-Host");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.strip();
            }
        }
        return request.getHeader("Host");
    }

    /** Effective origin for allowlist validation: direct unless trusted ingress overrides. */
    public static String effectiveOrigin(HttpServletRequest request, OwnerSecurityProperties properties) {
        if (useProxyHeaders(request, properties)) {
            String forwardedHost = singleHeader(request, "X-Forwarded-Host");
            String forwardedProto = singleHeader(request, "X-Forwarded-Proto");
            if (forwardedHost != null && !forwardedHost.isBlank()
                    && forwardedProto != null && !forwardedProto.isBlank()
                    && (forwardedProto.equalsIgnoreCase("http") || forwardedProto.equalsIgnoreCase("https"))) {
                return forwardedProto.strip().toLowerCase(java.util.Locale.ROOT)
                        + "://" + forwardedHost.strip();
            }
        }
        return request.getHeader("Origin");
    }

    public static List<String> allValues(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        return values == null ? List.of() : Collections.list(values);
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        List<String> values = allValues(request, name);
        if (values.size() != 1) {
            return null;
        }
        return values.getFirst();
    }
}
