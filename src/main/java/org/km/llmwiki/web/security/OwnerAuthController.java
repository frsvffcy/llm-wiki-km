package org.km.llmwiki.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.km.llmwiki.web.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Owner session admission API (#417 §A).
 *
 * <p>Single-user only: the request body carries exactly one password, checked
 * in constant time against the configured hash. A successful login mints an
 * opaque server-side session, sets it as an {@code HttpOnly} cookie for the
 * Browser ambient credential, and also returns the same value once as a
 * {@code token} field for non-Browser Bearer clients.
 *
 * <p>Credential tradeoff carried by executable tests: the cookie is
 * {@code HttpOnly} (resistant to token theft via script) but ambient, so
 * cookie mutations additionally require an allowlisted {@code Origin}; the
 * bearer token needs no origin check (non-ambient) but lives in client memory
 * and must therefore be short-lived, rotatable, and revocable. Both forms
 * share one server-side authority with identical expiry, logout, revocation,
 * rotation, and restart fail-closed semantics.
 */
@RestController
@RequestMapping("/api/v1/owner/session")
public class OwnerAuthController {

    private final OwnerSecurityProperties properties;
    private final OwnerSessionService sessions;

    public OwnerAuthController(OwnerSecurityProperties properties, OwnerSessionService sessions) {
        this.properties = properties;
        this.sessions = sessions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, Object>> login(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletResponse response) {
        if (!properties.authEnabled()) {
            throw new OwnerAuthenticationException("owner authentication is not enabled");
        }
        String password = body == null || !(body.get("password") instanceof String value)
                ? null : value;
        if (password == null || password.isEmpty() || password.length() > 512
                || !sessions.passwordMatches(password)) {
            throw new OwnerAuthenticationException("invalid owner credential");
        }
        OwnerSessionService.Login login = sessions.createSession();
        setSessionCookie(response, login.token());
        return new ApiResponse<>(Map.of(
                "authenticated", true,
                "token", login.token(),
                "absoluteTimeoutSeconds", login.absoluteTimeout().toSeconds(),
                "idleTimeoutSeconds", login.idleTimeout().toSeconds()));
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> status() {
        if (!properties.authEnabled()) {
            return new ApiResponse<>(Map.of("authEnabled", false, "authenticated", true));
        }
        return new ApiResponse<>(Map.of(
                "authEnabled", true,
                "authenticated", true,
                "absoluteTimeoutSeconds", properties.sessionAbsoluteTimeout().toSeconds(),
                "idleTimeoutSeconds", properties.sessionIdleTimeout().toSeconds()));
    }

    @PostMapping("/rotation")
    public ApiResponse<Map<String, Object>> rotate(
            HttpServletRequest request, HttpServletResponse response) {
        String presented = presentedToken(request);
        if (presented == null) {
            throw new OwnerAuthenticationException("session required");
        }
        return sessions.rotate(presented)
                .map(login -> {
                    setSessionCookie(response, login.token());
                    return new ApiResponse<Map<String, Object>>(Map.of(
                            "authenticated", true,
                            "token", login.token(),
                            "absoluteTimeoutSeconds", login.absoluteTimeout().toSeconds(),
                            "idleTimeoutSeconds", login.idleTimeout().toSeconds()));
                })
                .orElseThrow(() -> new OwnerAuthenticationException("session required"));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.revoke(presentedToken(request));
        setClearedCookie(response);
    }

    private String presentedToken(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if (properties.cookieName().equals(cookie.getName())
                        && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return cookie.getValue().strip();
                }
            }
        }
        List<String> authorizations = TrustedIngressPolicy.allValues(
                request, org.springframework.http.HttpHeaders.AUTHORIZATION);
        if (authorizations.size() == 1 && authorizations.getFirst().startsWith("Bearer ")) {
            String candidate = authorizations.getFirst().substring("Bearer ".length()).strip();
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    private void setSessionCookie(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie
                .from(properties.cookieName(), token)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .path("/")
                .maxAge(properties.sessionAbsoluteTimeout().toSeconds())
                .sameSite("Lax")
                .build()
                .toString());
    }

    private void setClearedCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie
                .from(properties.cookieName(), "")
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build()
                .toString());
    }
}
