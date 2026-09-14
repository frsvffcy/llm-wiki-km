package org.km.llmwiki.system;

import org.km.llmwiki.system.BrowserOriginPolicy.BrowserOrigin;
import org.km.llmwiki.web.security.HostOriginPolicy;
import org.km.llmwiki.web.security.OwnerSecurityProperties;

/**
 * Fail-fast validator for the explicit deployment profile (#418 §A/B, browser
 * ingress contract #422 §B/C/D).
 *
 * <p>Fixed enforcement order: raw backend bind stays loopback-only, then
 * single-instance, then forwarder target stays loopback, then per-mode
 * forwarder scope, browser-origin authority, and owner-authentication
 * prerequisite, then the cross-validation that makes a {@code SUPPORTED} claim
 * mean a Browser owner session can actually work: the owner Host/Origin
 * allowlists must accept the canonical browser ingress, the forwarder scope
 * must expose its port (and its address when the ingress host is an IP
 * literal), the cookie transport must match the ingress scheme, and proxy
 * locator headers must never be trusted without an explicit peer allowlist.
 * Any violation throws {@link IllegalStateException} with a fixed
 * operator-safe message (no addresses, paths, or secrets echoed) so startup
 * and redeploy fail closed and a restart can never silently widen exposure.
 *
 * <p>Network admission through a private network / VPN / overlay is never
 * treated as application authorization: every non-local mode additionally
 * requires the application-owned owner boundary (#417) to be enabled. Upstream
 * identity headers still never authenticate; that invariant stays with the
 * owner filter.
 *
 * <p>Cookie transport decision (#422 §C): {@code https} browser ingress
 * requires {@code cookie-secure=true} (HttpOnly Secure cookie over private
 * TLS); {@code http} browser ingress requires {@code cookie-secure=false} and
 * is only valid as an explicit bounded {@code PRIVATE_INGRESS} profile whose
 * transport encryption comes from the private network / VPN / overlay itself
 * (for example WireGuard, Tailscale, or SSH). Plain {@code http} over a public
 * or untrusted LAN is never a supported transport, and a {@code Secure} cookie
 * over a remote {@code http} ingress would never be sent by the Browser, so
 * that combination fails closed instead of reporting a fake-green session.
 */
public class DeploymentProfileValidator {

    static final String LOOPBACK_HOST = "127.0.0.1";

    private final DeploymentProperties deployment;
    private final OwnerSecurityProperties owner;
    private final String serverAddress;
    private final int serverPort;

    public DeploymentProfileValidator(
            DeploymentProperties deployment,
            OwnerSecurityProperties owner,
            String serverAddress,
            int serverPort) {
        this.deployment = deployment;
        this.owner = owner;
        this.serverAddress = serverAddress == null ? "" : serverAddress.strip();
        this.serverPort = serverPort;
    }

    /** Re-runnable so restart/redeploy re-applies the same closed boundary. */
    public void validate() {
        validateBackendBind();
        validateSingleInstance();
        validateForwarderTarget();
        switch (deployment.mode()) {
            case LOCAL_ONLY -> validateLocalOnly();
            case PRIVATE_INGRESS -> validatePrivateIngress();
            case REVERSE_PROXY_CANDIDATE -> validateReverseProxyCandidate();
        }
    }

    private void validateBackendBind() {
        if (!LOOPBACK_HOST.equals(serverAddress)) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: the raw backend must stay bound to loopback");
        }
        if (serverPort < 1 || serverPort > 65535) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: the backend port is out of range");
        }
    }

    private void validateSingleInstance() {
        if (deployment.maxInstances() != 1) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: only a single instance may own writable state");
        }
    }

    private void validateForwarderTarget() {
        String target = deployment.forwarderTarget();
        int separator = target.lastIndexOf(':');
        if (separator < 0) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: the forwarder target must address the loopback backend");
        }
        String host = target.substring(0, separator).strip();
        String portText = target.substring(separator + 1).strip();
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException malformed) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: the forwarder target must address the loopback backend");
        }
        if (!LOOPBACK_HOST.equals(host) || port != serverPort) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: the forwarder target must address the loopback backend");
        }
    }

    private void validateLocalOnly() {
        if (!deployment.forwarderBinds().isEmpty()) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: local-only mode declares no forwarder scope");
        }
        if (!deployment.browserOrigin().isEmpty()) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: local-only mode declares no browser origin");
        }
    }

    private void validatePrivateIngress() {
        if (deployment.forwarderBinds().isEmpty()) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: private ingress requires explicit forwarder scope");
        }
        if (deployment.browserOrigin().isEmpty()) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: private ingress requires an explicit browser origin");
        }
        var binds = validateForwarderScope();
        BrowserOrigin browser = parseBrowserOrigin();
        if (BrowserOriginPolicy.isLoopbackHost(browser.host())) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: private ingress requires a non-loopback browser origin");
        }
        requireOwnerAuth();
        requireExplicitProxyTrust();
        requireAllowlistAcceptance(browser);
        requireForwarderCompatibility(binds, browser);
        requireCookieTransport(browser);
    }

    private void validateReverseProxyCandidate() {
        var binds = validateForwarderScope();
        requireOwnerAuth();
        requireExplicitProxyTrust();
        // The candidate direction keeps its topology contract but never reports
        // SUPPORTED; a declared browser origin is cross-validated when present so
        // candidate documentation cannot drift from the same ingress truth.
        if (!deployment.browserOrigin().isEmpty()) {
            BrowserOrigin browser = parseBrowserOrigin();
            requireAllowlistAcceptance(browser);
            requireForwarderCompatibility(binds, browser);
            requireCookieTransport(browser);
        }
    }

    private record ForwarderBind(String host, int port) {
    }

    private java.util.List<ForwarderBind> validateForwarderScope() {
        java.util.List<ForwarderBind> parsed = new java.util.ArrayList<>();
        for (String bind : deployment.forwarderBinds()) {
            if (isWildcardBind(bind)) {
                throw new IllegalStateException(
                        "Deployment profile is invalid: forwarder scope must not bind all interfaces");
            }
            parsed.add(parseForwarderBind(bind));
        }
        return java.util.List.copyOf(parsed);
    }

    static ForwarderBind parseForwarderBind(String bind) {
        String value = bind == null ? "" : bind.strip();
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: forwarder scope must declare an explicit host and port");
        }
        String host = value.substring(0, separator).strip();
        String portText = value.substring(separator + 1).strip();
        if (host.isEmpty() || host.indexOf(',') >= 0 || host.indexOf('@') >= 0
                || host.indexOf(' ') >= 0 || host.indexOf('/') >= 0
                || host.indexOf('?') >= 0 || host.indexOf('#') >= 0) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: forwarder scope must declare an explicit host and port");
        }
        if (host.startsWith("[") != host.endsWith("]")) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: forwarder scope must declare an explicit host and port");
        }
        String bareHost = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1).strip()
                : host;
        if (bareHost.isEmpty() || (!host.startsWith("[") && bareHost.indexOf(':') >= 0)) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: forwarder scope must declare an explicit host and port");
        }
        int port;
        try {
            if (!portText.chars().allMatch(Character::isDigit)) {
                throw new NumberFormatException("non-numeric port");
            }
            port = Integer.parseInt(portText);
        } catch (NumberFormatException malformed) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: forwarder scope must declare an explicit host and port");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: forwarder scope must declare an explicit host and port");
        }
        return new ForwarderBind(bareHost.toLowerCase(java.util.Locale.ROOT), port);
    }

    private BrowserOrigin parseBrowserOrigin() {
        try {
            return BrowserOriginPolicy.parse(deployment.browserOrigin());
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException(malformed.getMessage());
        }
    }

    private void requireOwnerAuth() {
        if (owner == null || !owner.authEnabled()) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: non-local ingress requires owner authentication");
        }
    }

    private void requireExplicitProxyTrust() {
        if (owner != null && owner.trustProxyHeaders() && owner.trustedProxies().isEmpty()) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: proxy-header trust requires explicit trusted proxies");
        }
    }

    private void requireAllowlistAcceptance(BrowserOrigin browser) {
        if (owner == null
                || !HostOriginPolicy.validHost(browser.host(), owner.allowedHosts())
                || !HostOriginPolicy.validOrigin(browser.canonical(), owner.allowedOrigins())) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: the owner allowlists must accept the browser ingress");
        }
    }

    private void requireForwarderCompatibility(
            java.util.List<ForwarderBind> binds, BrowserOrigin browser) {
        boolean portExposed = binds.stream().anyMatch(bind -> bind.port() == browser.port());
        if (!portExposed) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: forwarder scope must expose the browser ingress port");
        }
        // IP-literal ingress must name its listener exactly; DNS names stay the
        // operator's resolution responsibility (documented in the runbook) so the
        // validator stays deterministic and offline-safe.
        if (BrowserOriginPolicy.isIpLiteral(browser.host())) {
            boolean addressExposed = binds.stream().anyMatch(bind ->
                    bind.port() == browser.port() && bind.host().equalsIgnoreCase(browser.host()));
            if (!addressExposed) {
                throw new IllegalStateException(
                        "Deployment profile is invalid: forwarder scope must expose the browser ingress address");
            }
        }
    }

    private void requireCookieTransport(BrowserOrigin browser) {
        if (owner == null) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: non-local ingress requires owner authentication");
        }
        boolean secure = owner.cookieSecure();
        if ("https".equals(browser.scheme()) && !secure) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: secure browser ingress requires a secure cookie transport");
        }
        if ("http".equals(browser.scheme()) && secure) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: plain browser ingress requires an explicit private-tunnel cookie transport");
        }
    }

    static boolean isWildcardBind(String bind) {
        String value = bind.strip().toLowerCase(java.util.Locale.ROOT);
        return value.isEmpty()
                || value.equals("*")
                || value.startsWith("*:")
                || value.equals("0.0.0.0")
                || value.startsWith("0.0.0.0:")
                || value.equals("::")
                || value.startsWith("[::]")
                || value.equals("0:0:0:0:0:0:0:0")
                || value.startsWith("0:0:0:0:0:0:0:0:");
    }
}
