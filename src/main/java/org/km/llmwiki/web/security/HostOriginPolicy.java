package org.km.llmwiki.web.security;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Pure Host / Origin allowlist validation for {@code /api/v1} (#417 §C).
 *
 * <p>Default deny: a missing, duplicated, or malformed {@code Host} fails
 * closed; an {@code Origin} that is present must be an exact allowlisted
 * origin, otherwise the request fails closed. The policy never emits a
 * wildcard allow-origin together with credentials.
 */
public final class HostOriginPolicy {

    private HostOriginPolicy() {
    }

    public static boolean validHost(String value, List<String> allowedHosts) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.indexOf(',') >= 0 || value.indexOf('@') >= 0) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        int colon = normalized.indexOf(':');
        String hostname = colon < 0 ? normalized : normalized.substring(0, colon);
        if (hostname.isEmpty()) {
            return false;
        }
        boolean allowed = allowedHosts.stream()
                .anyMatch(candidate -> candidate.equalsIgnoreCase(hostname));
        if (!allowed) {
            return false;
        }
        if (colon < 0) {
            return true;
        }
        if (normalized.indexOf(':', colon + 1) >= 0) {
            return false;
        }
        return validPort(normalized.substring(colon + 1));
    }

    public static boolean validOrigin(String value, List<String> allowedOrigins) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.equalsIgnoreCase("null") || value.indexOf(',') >= 0) {
            return false;
        }
        String normalized = canonicalOrigin(value);
        if (normalized == null) {
            return false;
        }
        return allowedOrigins.stream().anyMatch(candidate -> {
            String canonicalCandidate = canonicalOrigin(candidate);
            return normalized.equals(canonicalCandidate);
        });
    }

    static String canonicalOrigin(String value) {
        try {
            URI origin = URI.create(value);
            String scheme = origin.getScheme();
            String host = origin.getHost();
            if (scheme == null || host == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                    || origin.getRawFragment() != null
                    || origin.getRawPath() != null && !origin.getRawPath().isEmpty()
                    && !origin.getRawPath().equals("/")) {
                return null;
            }
            int port = origin.getPort();
            if (!(port == -1 || port >= 1 && port <= 65_535)) {
                return null;
            }
            String expectedAuthority = port == -1 ? host : host + ":" + port;
            if (!expectedAuthority.equalsIgnoreCase(origin.getRawAuthority())) {
                return null;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            String path = "";
            if (port == -1) {
                return normalizedScheme + "://" + normalizedHost;
            }
            return normalizedScheme + "://" + normalizedHost + ":" + port + path;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static boolean validPort(String value) {
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            return false;
        }
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65_535;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }
}
