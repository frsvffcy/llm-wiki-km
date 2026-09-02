package org.km.llmwiki.search.vector;

import java.util.Set;

/** Immutable, deterministic result of a vector capability probe. */
public record VectorCapabilityReport(
        VectorAvailability availability,
        VectorHealth health,
        VectorExtensionLoadStatus extensionLoadStatus,
        int requestedDimension,
        VectorEncoding requestedEncoding,
        boolean dimensionSupported,
        Set<VectorEncoding> supportedEncodings,
        VectorCapabilityFailure failure,
        String detail,
        String runtimeVersion) {

    public VectorCapabilityReport {
        if (availability == null || health == null || extensionLoadStatus == null
                || requestedEncoding == null || supportedEncodings == null || failure == null
                || detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("Vector capability report fields are required");
        }
        supportedEncodings = Set.copyOf(supportedEncodings);
        if (availability == VectorAvailability.AVAILABLE
                && (health != VectorHealth.HEALTHY || failure != VectorCapabilityFailure.NONE)) {
            throw new IllegalArgumentException("Available vector capability must be healthy");
        }
        if (availability == VectorAvailability.UNAVAILABLE
                && failure == VectorCapabilityFailure.NONE) {
            throw new IllegalArgumentException("Unavailable vector capability needs a failure code");
        }
    }

    public static VectorCapabilityReport unavailable(VectorCapabilityRequest request,
                                                     VectorCapabilityFailure failure,
                                                     VectorExtensionLoadStatus loadStatus,
                                                     String detail,
                                                     String runtimeVersion) {
        return new VectorCapabilityReport(VectorAvailability.UNAVAILABLE, VectorHealth.UNHEALTHY,
                loadStatus, request.dimension(), request.encoding(), false, Set.of(), failure,
                detail, runtimeVersion);
    }

    public static VectorCapabilityReport available(VectorCapabilityRequest request,
                                                   Set<VectorEncoding> supportedEncodings,
                                                   String runtimeVersion) {
        return new VectorCapabilityReport(VectorAvailability.AVAILABLE, VectorHealth.HEALTHY,
                VectorExtensionLoadStatus.LOADED, request.dimension(), request.encoding(), true,
                supportedEncodings, VectorCapabilityFailure.NONE, "Vector capability is ready",
                runtimeVersion);
    }
}
