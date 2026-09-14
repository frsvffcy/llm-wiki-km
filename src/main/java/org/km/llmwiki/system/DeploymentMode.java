package org.km.llmwiki.system;

/**
 * Single-instance Remote Personal Deployment modes (#418,承接 #393 evaluation).
 *
 * <p>{@code LOCAL_ONLY} is the current supported baseline (Mode 0).
 * {@code PRIVATE_INGRESS} is the Mode 1 adoption target: remote traffic arrives
 * through a private network / VPN / overlay, then a bounded host-local
 * forwarder delivers it to the loopback backend. Overlay traffic never hits the
 * loopback listener directly. {@code REVERSE_PROXY_CANDIDATE} is the Mode 2
 * public HTTPS direction: it stays a candidate and is never reported
 * {@code SUPPORTED} by this operations contract. Direct raw application
 * Internet bind (Mode 3) has no enum value on purpose: it stays rejected and
 * any attempt to configure it fails fast in the profile validator.
 */
public enum DeploymentMode {
    LOCAL_ONLY,
    PRIVATE_INGRESS,
    REVERSE_PROXY_CANDIDATE
}
