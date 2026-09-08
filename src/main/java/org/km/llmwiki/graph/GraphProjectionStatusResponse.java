package org.km.llmwiki.graph;

/**
 * Safe, provider-neutral public projection of one graph projection verification for the
 * operational REST surface.
 *
 * <p>The mapping is intentionally narrow: readiness status, lifecycle generations, the active
 * operation kind, and the typed failure taxonomy (public code plus the already-sanitized
 * failure diagnostic) travel to the operator; source fingerprints, snapshot tokens, owner
 * tokens, filesystem paths, backend record identities, and raw exceptions never do. The
 * contract stays stable if the projection backend is replaced: every field is
 * application-owned, and {@code provider} is informational only.
 */
public record GraphProjectionStatusResponse(
        long workspaceId,
        String provider,
        String projectionVersion,
        String status,
        long targetGeneration,
        long appliedGeneration,
        String operationKind,
        String failureCode,
        String failureDiagnostic,
        String lastFailureCode,
        boolean retryable,
        boolean repairRecommended
) {

    public static GraphProjectionStatusResponse from(GraphProjectionVerification verification) {
        GraphProjectionReadiness control = verification.controlPlane();
        GraphProjectionFailure failure = verification.failure();
        GraphProjectionFailureType lastFailure = control == null ? null : control.lastFailureType();
        boolean repairRecommended = verification.status()
                == GraphProjectionVerificationStatus.REPAIR_REQUIRED
                || failure != null && (failure.type() == GraphProjectionFailureType.PROJECTION_CORRUPT
                || failure.type() == GraphProjectionFailureType.PROJECTION_STALE);
        return new GraphProjectionStatusResponse(
                verification.workspace().id(),
                control == null ? null : control.provider(),
                control == null ? null : control.projectionVersion().value(),
                verification.status().name(),
                control == null ? 0 : control.targetGeneration(),
                control == null ? 0 : control.appliedGeneration(),
                control == null || control.activeOperation() == null
                        ? null : control.activeOperation().kind().name(),
                failure == null ? null : failure.publicCode(),
                failure == null ? null : failure.diagnostic(),
                lastFailure == null ? null : lastFailure.publicCode(),
                failure != null && failure.type().retryable(),
                repairRecommended
        );
    }
}
