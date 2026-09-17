package org.km.llmwiki.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.km.llmwiki.web.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Application-owned remote security boundary for {@code /api/v1} (#417).
 *
 * <p>Fixed decision order per request:
 * host allowlist → origin allowlist with safe cross-origin headers →
 * login admission (rate-limited, no session) or session authentication
 * (cookie ambient or bearer explicit) → cookie-mutation origin equivalence →
 * mutation rate limit → pass through.
 *
 * <p>Security properties preserved from the #393 baseline:
 * authentication never bypasses workspace, currentness, Proposal, Draft,
 * Human Review, explicit Publish, repair revalidation, or Evidence authority —
 * this filter only decides admission and then passes the request to the
 * unchanged domain controllers. Upstream identity headers never authenticate.
 * The MCP adapter ({@code /api/mcp}) keeps its own loopback boundary and is
 * never admitted by an owner session.
 *
 * <p>Failures are written directly as {@link ApiError} JSON because filter
 * rejections never reach the controller advice. Bodies carry only stable codes
 * and fixed messages — never session material, secrets, paths, or backend
 * identities.
 */
public class OwnerSecurityFilter extends OncePerRequestFilter {

    static final String LOGIN_PATH = "/api/v1/owner/session";
    static final String ROTATION_PATH = "/api/v1/owner/session/rotation";

    private static final Logger log = LoggerFactory.getLogger(OwnerSecurityFilter.class);

    private final OwnerSecurityProperties properties;
    private final OwnerSessionService sessions;
    private final OwnerRateLimiter rateLimiter;
    private final ObjectMapper mapper = new ObjectMapper();

    public OwnerSecurityFilter(
            OwnerSecurityProperties properties,
            OwnerSessionService sessions,
            OwnerRateLimiter rateLimiter) {
        this.properties = properties;
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        return !path.startsWith("/api/v1");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.authEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        try {
            apply(request, response, chain);
        } catch (OwnerHostRejectedException rejected) {
            writeError(request, response, HttpServletResponse.SC_FORBIDDEN,
                    "OWNER_HOST_REJECTED", "不允許此要求主機");
        } catch (OwnerOriginRejectedException rejected) {
            writeError(request, response, HttpServletResponse.SC_FORBIDDEN,
                    "OWNER_ORIGIN_REJECTED", "不允許此要求來源");
        } catch (OwnerAuthenticationException rejected) {
            writeError(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                    "OWNER_AUTH_REQUIRED", "需要擁有者驗證");
        } catch (OwnerRateLimitedException rejected) {
            writeError(request, response, 429,
                    "OWNER_RATE_LIMITED", "要求次數過多，請稍後再試");
        }
    }

    private void apply(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = path(request);
        String method = request.getMethod().toUpperCase(java.util.Locale.ROOT);

        String host = TrustedIngressPolicy.effectiveHost(request, properties);
        List<String> hostValues = hostValues(request);
        if (hostValues.size() != 1 || !HostOriginPolicy.validHost(host, properties.allowedHosts())) {
            throw new OwnerHostRejectedException("host rejected");
        }

        List<String> originValues = TrustedIngressPolicy.allValues(request, "Origin");
        if (originValues.size() > 1) {
            throw new OwnerOriginRejectedException("origin rejected");
        }
        String origin = TrustedIngressPolicy.effectiveOrigin(request, properties);
        boolean originPresent = origin != null && !origin.isBlank();
        boolean originAllowed = originPresent
                && HostOriginPolicy.validOrigin(origin, properties.allowedOrigins());
        if (originPresent && !originAllowed) {
            applySafeHeaders(response, null);
            throw new OwnerOriginRejectedException("origin rejected");
        }
        applySafeHeaders(response, originAllowed ? origin.strip() : null);

        if ("OPTIONS".equals(method)) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        if (isLoginAttempt(path, method)) {
            String client = request.getRemoteAddr();
            if (!rateLimiter.tryAcquire(OwnerRateLimiter.Bucket.LOGIN, client,
                    properties.loginMaxAttempts(), properties.loginWindow())) {
                throw new OwnerRateLimitedException("login throttled");
            }
            chain.doFilter(request, response);
            return;
        }

        Credential credential = extract(request);
        if (credential.token() == null
                || sessions.validate(credential.token()).isEmpty()) {
            throw new OwnerAuthenticationException("session required");
        }

        if (isMutation(method)) {
            if (credential.cookie()) {
                if (!originAllowed) {
                    throw new OwnerOriginRejectedException("cookie mutation needs an allowlisted origin");
                }
            }
            String client = request.getRemoteAddr();
            if (!rateLimiter.tryAcquire(OwnerRateLimiter.Bucket.MUTATION, client,
                    properties.mutationMaxRequests(), properties.mutationWindow())) {
                throw new OwnerRateLimitedException("mutation throttled");
            }
        }
        chain.doFilter(request, response);
    }

    private record Credential(String token, boolean cookie) {
    }

    private Credential extract(HttpServletRequest request) {
        String cookieToken = cookieToken(request);
        if (cookieToken != null) {
            return new Credential(cookieToken, true);
        }
        List<String> authorizations = TrustedIngressPolicy.allValues(request, HttpHeaders.AUTHORIZATION);
        if (authorizations.size() > 1) {
            return new Credential(null, false);
        }
        if (authorizations.size() == 1) {
            String value = authorizations.getFirst();
            if (!value.startsWith("Bearer ")) {
                return new Credential(null, false);
            }
            String candidate = value.substring("Bearer ".length()).strip();
            if (candidate.isEmpty()) {
                return new Credential(null, false);
            }
            return new Credential(candidate, false);
        }
        return new Credential(null, false);
    }

    private String cookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String found = null;
        for (Cookie cookie : cookies) {
            if (properties.cookieName().equals(cookie.getName())) {
                if (found != null) {
                    return null;
                }
                found = cookie.getValue();
            }
        }
        if (found == null || found.isBlank()) {
            return null;
        }
        return found.strip();
    }

    private List<String> hostValues(HttpServletRequest request) {
        if (TrustedIngressPolicy.useProxyHeaders(request, properties)) {
            List<String> forwarded = TrustedIngressPolicy.allValues(request, "X-Forwarded-Host");
            if (!forwarded.isEmpty()) {
                return forwarded;
            }
        }
        return TrustedIngressPolicy.allValues(request, "Host");
    }

    private void applySafeHeaders(HttpServletResponse response, String allowedOrigin) {
        response.setHeader("Vary", "Origin");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (allowedOrigin != null) {
            response.setHeader("Access-Control-Allow-Origin", allowedOrigin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
            response.setHeader("Access-Control-Allow-Headers",
                    "Content-Type,Authorization,X-Requested-With");
            response.setHeader("Access-Control-Max-Age", "600");
        }
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response,
                            int status, String code, String message) throws IOException {
        applySafeHeaders(response, allowlistedOriginOf(request));
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), ApiError.of(code, message));
        log.debug("Owner security rejection {} for {} {}", code,
                request.getMethod(), path(request));
    }

    private String allowlistedOriginOf(HttpServletRequest request) {
        List<String> values = TrustedIngressPolicy.allValues(request, "Origin");
        if (values.size() != 1) {
            return null;
        }
        String origin = TrustedIngressPolicy.effectiveOrigin(request, properties);
        if (origin != null && !origin.isBlank()
                && HostOriginPolicy.validOrigin(origin, properties.allowedOrigins())) {
            return origin.strip();
        }
        return null;
    }

    private static boolean isLoginAttempt(String path, String method) {
        return "POST".equals(method) && LOGIN_PATH.equals(path);
    }

    static boolean isMutation(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null ? "" : uri;
    }
}
