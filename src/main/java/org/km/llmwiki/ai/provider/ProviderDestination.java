package org.km.llmwiki.ai.provider;

/**
 * Application-owned provider destination classification, derived from the backend's current
 * configuration and the #281 transport policy. It is a transparency projection for the
 * Browser, never a provider capability/readiness claim, and it is never derived from a URL the
 * Browser parsed by itself.
 */
public enum ProviderDestination {
    /** The provider is not enabled in the current configuration. */
    DISABLED,
    /** The configured endpoint targets the local machine (loopback host). */
    LOCAL_LOOPBACK,
    /** The configured endpoint is HTTPS to a non-loopback host. */
    REMOTE_SECURE,
    /** The configured endpoint is plain HTTP to a non-loopback host with the explicit opt-in. */
    REMOTE_INSECURE_OPT_IN,
    /** The configured endpoint is malformed, invalid, or rejected by the transport policy. */
    UNAVAILABLE_OR_INVALID
}
