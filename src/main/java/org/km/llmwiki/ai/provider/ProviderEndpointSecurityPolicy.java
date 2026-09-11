package org.km.llmwiki.ai.provider;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Shared application-owned security policy for backend provider endpoints.
 *
 * <p>HTTPS endpoints may target any host. Plain HTTP is limited to loopback hosts unless the
 * caller explicitly opts into insecure transport. This policy validates the base URI before an
 * adapter can construct or send a transport request; it never resolves arbitrary host names.
 */
public final class ProviderEndpointSecurityPolicy {

    private static final int MAX_BASE_URL_LENGTH = 2_048;
    private static final Pattern NUMERIC_IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

    private ProviderEndpointSecurityPolicy() {
    }

    /**
     * Validates a provider base URL and appends an application-owned endpoint path.
     *
     * @param baseUrl configured provider base URL
     * @param endpointPath application-owned endpoint path, such as {@code /embeddings}
     * @param allowInsecureTransport explicit opt-in for non-loopback plain HTTP
     * @return validated endpoint URI
     * @throws IllegalArgumentException when the URI violates the provider transport policy
     */
    public static URI validateAndAppend(String baseUrl, String endpointPath,
                                        boolean allowInsecureTransport) {
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.length() > MAX_BASE_URL_LENGTH
                || endpointPath == null || !endpointPath.startsWith("/")
                || endpointPath.indexOf('?') >= 0 || endpointPath.indexOf('#') >= 0) {
            throw new IllegalArgumentException("provider endpoint is invalid");
        }

        URI base;
        try {
            base = new URI(baseUrl.trim());
        } catch (URISyntaxException exception) {
            throw invalidEndpoint();
        }
        String scheme = base.getScheme();
        String host = base.getHost();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                || host == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null) {
            throw invalidEndpoint();
        }
        if ("http".equalsIgnoreCase(scheme)
                && !allowInsecureTransport && !isLoopbackHost(host)) {
            throw new IllegalArgumentException("provider endpoint requires secure transport");
        }

        String normalized = base.toString().replaceFirst("/+$", "");
        try {
            return new URI(normalized + endpointPath);
        } catch (URISyntaxException exception) {
            throw invalidEndpoint();
        }
    }

    private static IllegalArgumentException invalidEndpoint() {
        return new IllegalArgumentException("provider endpoint is invalid");
    }

    /**
     * Classifies a configured provider endpoint for the transparency contract without leaking
     * the URL. Classification reuses this policy's own validation and loopback rules, so the
     * disclosure can never disagree with the transport policy: a configuration the policy
     * would reject (for example plain HTTP without the explicit opt-in) is classified as
     * {@link ProviderDestination#UNAVAILABLE_OR_INVALID}, and a disabled or missing endpoint
     * is classified by the caller.
     *
     * @param baseUrl configured provider base URL
     * @param allowInsecureTransport explicit opt-in for non-loopback plain HTTP
     * @return application-owned destination classification
     */
    public static ProviderDestination classify(String baseUrl, boolean allowInsecureTransport) {
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.length() > MAX_BASE_URL_LENGTH) {
            return ProviderDestination.UNAVAILABLE_OR_INVALID;
        }
        URI base;
        try {
            base = new URI(baseUrl.trim());
        } catch (URISyntaxException exception) {
            return ProviderDestination.UNAVAILABLE_OR_INVALID;
        }
        String scheme = base.getScheme();
        String host = base.getHost();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                || host == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null) {
            return ProviderDestination.UNAVAILABLE_OR_INVALID;
        }
        boolean loopback = isLoopbackHost(host);
        if ("https".equalsIgnoreCase(scheme)) {
            return loopback ? ProviderDestination.LOCAL_LOOPBACK
                    : ProviderDestination.REMOTE_SECURE;
        }
        if (loopback) {
            return ProviderDestination.LOCAL_LOOPBACK;
        }
        return allowInsecureTransport ? ProviderDestination.REMOTE_INSECURE_OPT_IN
                : ProviderDestination.UNAVAILABLE_OR_INVALID;
    }

    private static boolean isLoopbackHost(String host) {
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        if (host.length() >= 2 && host.startsWith("[") && host.endsWith("]")) {
            return isLoopbackIpv6Literal(host.substring(1, host.length() - 1));
        }
        return isIpv4LoopbackLiteral(host);
    }

    private static boolean isLoopbackIpv6Literal(String literal) {
        // The bracketed host came from URI parsing. Restrict the value to numeric IPv6 syntax
        // before using InetAddress, so this path can never trigger DNS resolution.
        if (!NUMERIC_IPV6_LITERAL.matcher(literal).matches()) {
            return false;
        }
        try {
            return InetAddress.getByName(literal).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private static boolean isIpv4LoopbackLiteral(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        int firstOctet = parseCanonicalOctet(octets[0]);
        if (firstOctet != 127) {
            return false;
        }
        for (String octet : octets) {
            if (parseCanonicalOctet(octet) < 0) {
                return false;
            }
        }
        return true;
    }

    private static int parseCanonicalOctet(String octet) {
        if (octet.isEmpty() || (octet.length() > 1 && octet.charAt(0) == '0')) {
            return -1;
        }
        int value = 0;
        for (int index = 0; index < octet.length(); index++) {
            char digit = octet.charAt(index);
            if (digit < '0' || digit > '9') {
                return -1;
            }
            value = value * 10 + digit - '0';
            if (value > 255) {
                return -1;
            }
        }
        return value;
    }
}
