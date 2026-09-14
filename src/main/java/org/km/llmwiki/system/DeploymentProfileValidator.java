package org.km.llmwiki.system;

import org.km.llmwiki.web.security.OwnerSecurityProperties;

/**
 * Fail-fast validator for the explicit deployment profile (#418 §A/B).
 *
 * <p>Fixed enforcement order: raw backend bind stays loopback-only, then
 * single-instance, then forwarder target stays loopback, then per-mode
 * forwarder scope and owner-authentication prerequisite. Any violation throws
 * {@link IllegalStateException} with a fixed operator-safe message (no
 * addresses, paths, or secrets echoed) so startup and redeploy fail closed and
 * a restart can never silently widen exposure.
 *
 * <p>Network admission through a private network / VPN / overlay is never
 * treated as application authorization: every non-local mode additionally
 * requires the application-owned owner boundary (#417) to be enabled. Upstream
 * identity headers still never authenticate; that invariant stays with the
 * owner filter.
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
    }

    private void validatePrivateIngress() {
        if (deployment.forwarderBinds().isEmpty()) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: private ingress requires explicit forwarder scope");
        }
        validateForwarderScope();
        requireOwnerAuth();
    }

    private void validateReverseProxyCandidate() {
        validateForwarderScope();
        requireOwnerAuth();
    }

    private void validateForwarderScope() {
        for (String bind : deployment.forwarderBinds()) {
            if (isWildcardBind(bind)) {
                throw new IllegalStateException(
                        "Deployment profile is invalid: forwarder scope must not bind all interfaces");
            }
        }
    }

    private void requireOwnerAuth() {
        if (owner == null || !owner.authEnabled()) {
            throw new IllegalStateException(
                    "Deployment profile is invalid: non-local ingress requires owner authentication");
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
