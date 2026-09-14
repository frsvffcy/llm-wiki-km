package org.km.llmwiki.system;

import org.km.llmwiki.web.security.OwnerSecurityProperties;

/**
 * Read-only deployment readiness for operators (#418 §A/I).
 *
 * <p>Re-validates the explicit profile on every read so restart/redeploy can
 * never leave a stale supported claim behind: an invalid profile reports
 * {@code NOT_READY} instead of throwing, while startup itself still fails fast
 * through {@link DeploymentConfiguration}. {@code REVERSE_PROXY_CANDIDATE} is
 * always reported {@code CANDIDATE} even when its topology validates, because
 * public HTTPS support needs a future adoption beyond this issue.
 */
public class DeploymentReadinessService {

    private final DeploymentProperties deployment;
    private final OwnerSecurityProperties owner;
    private final String serverAddress;
    private final int serverPort;

    public DeploymentReadinessService(
            DeploymentProperties deployment,
            OwnerSecurityProperties owner,
            String serverAddress,
            int serverPort) {
        this.deployment = deployment;
        this.owner = owner;
        this.serverAddress = serverAddress == null ? "" : serverAddress.strip();
        this.serverPort = serverPort;
    }

    public DeploymentReadiness current() {
        try {
            new DeploymentProfileValidator(deployment, owner, serverAddress, serverPort).validate();
        } catch (IllegalStateException invalid) {
            return notReady();
        }
        return switch (deployment.mode()) {
            case LOCAL_ONLY -> new DeploymentReadiness(
                    DeploymentMode.LOCAL_ONLY.name(),
                    DeploymentReadiness.SUPPORTED,
                    backendBind(),
                    true,
                    true,
                    "Local-only deployment on the loopback backend.");
            case PRIVATE_INGRESS -> new DeploymentReadiness(
                    DeploymentMode.PRIVATE_INGRESS.name(),
                    DeploymentReadiness.SUPPORTED,
                    backendBind(),
                    true,
                    true,
                    "Private-ingress deployment with a validated browser origin,"
                            + " allowlisted ingress, and matching cookie transport"
                            + " to the loopback backend.");
            case REVERSE_PROXY_CANDIDATE -> new DeploymentReadiness(
                    DeploymentMode.REVERSE_PROXY_CANDIDATE.name(),
                    DeploymentReadiness.CANDIDATE,
                    backendBind(),
                    !deployment.forwarderBinds().isEmpty(),
                    true,
                    "Reverse-proxy TLS deployment is a candidate and is not supported"
                            + " by the current operations contract.");
        };
    }

    private DeploymentReadiness notReady() {
        String modeName;
        try {
            modeName = deployment.mode().name();
        } catch (RuntimeException unknown) {
            modeName = DeploymentMode.LOCAL_ONLY.name();
        }
        return new DeploymentReadiness(
                modeName,
                DeploymentReadiness.NOT_READY,
                backendBind(),
                false,
                deployment.maxInstances() == 1,
                "Deployment profile is invalid; the application is not ready for remote use.");
    }

    private String backendBind() {
        if (serverPort < 1 || serverPort > 65535) {
            return DeploymentProfileValidator.LOOPBACK_HOST + ":8765";
        }
        return DeploymentProfileValidator.LOOPBACK_HOST + ":" + serverPort;
    }
}
