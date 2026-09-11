package org.km.llmwiki.mcp;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Exact loopback Host/Origin validation used before authentication or body parsing. */
final class McpTransportSecurityGuard {

    private McpTransportSecurityGuard() {
    }

    static boolean allows(HttpServletRequest request) {
        List<String> hosts = headerValues(request, "Host");
        if (hosts.size() != 1 || !validHost(hosts.getFirst())) {
            return false;
        }
        List<String> origins = headerValues(request, "Origin");
        return origins.isEmpty() || origins.size() == 1 && validOrigin(origins.getFirst());
    }

    private static List<String> headerValues(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        return values == null ? List.of() : Collections.list(values);
    }

    static boolean validHost(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.indexOf(',') >= 0 || value.indexOf('@') >= 0) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        int colon = normalized.indexOf(':');
        String hostname = colon < 0 ? normalized : normalized.substring(0, colon);
        if (!hostname.equals("localhost") && !hostname.equals("127.0.0.1")) {
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

    static boolean validOrigin(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.equalsIgnoreCase("null") || value.indexOf(',') >= 0) {
            return false;
        }
        try {
            URI origin = URI.create(value);
            String scheme = origin.getScheme();
            String host = origin.getHost();
            if (scheme == null || host == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || !(host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1"))
                    || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                    || origin.getRawFragment() != null
                    || origin.getRawPath() != null && !origin.getRawPath().isEmpty()) {
                return false;
            }
            int port = origin.getPort();
            if (!(port == -1 || port >= 1 && port <= 65_535)) {
                return false;
            }
            String expectedAuthority = port == -1 ? host : host + ":" + port;
            return expectedAuthority.equalsIgnoreCase(origin.getRawAuthority());
        } catch (IllegalArgumentException invalid) {
            return false;
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
