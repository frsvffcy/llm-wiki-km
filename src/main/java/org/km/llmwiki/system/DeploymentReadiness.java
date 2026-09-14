package org.km.llmwiki.system;

/**
 * Operator-safe deployment readiness projection (#418 §A/I).
 *
 * <p>Carries only stable mode/support-state vocabulary, the loopback backend
 * bind, and bounded booleans. It never contains filesystem paths, secrets,
 * session material, backend identities, or provider payloads.
 */
public record DeploymentReadiness(
        String mode,
        String supportState,
        String backendBind,
        boolean forwarderBounded,
        boolean singleInstance,
        String reason) {

    /** Local-only baseline: the currently supported product mode. */
    public static final String SUPPORTED = "SUPPORTED";

    /** Public HTTPS direction: an explicit candidate, never supported here. */
    public static final String CANDIDATE = "CANDIDATE";

    /** Invalid profile: fail closed, not ready for any remote use. */
    public static final String NOT_READY = "NOT_READY";
}
