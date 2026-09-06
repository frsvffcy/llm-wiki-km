package org.km.llmwiki.graph;

/** Safe SQLite control-plane view; it contains no backend path or record identity. */
public record GraphProjectionReadiness(GraphWorkspaceScope workspace, String provider,
                                       GraphProjectionVersion projectionVersion,
                                       GraphProjectionReadinessStatus status,
                                       long targetGeneration, long appliedGeneration,
                                       String sourceFingerprint, String snapshotToken,
                                       GraphProjectionOperationKind operationKind,
                                       String operationOwner, String operationSourceFingerprint,
                                       String operationStartedAt,
                                       GraphProjectionFailureType lastFailureType,
                                       String diagnosticCode, String lastSuccessfulAt,
                                       String updatedAt) {
    public GraphProjectionReadiness {
        if (workspace == null || provider == null || provider.isBlank()
                || projectionVersion == null || status == null
                || targetGeneration < 0 || appliedGeneration < 0
                || appliedGeneration > targetGeneration || updatedAt == null) {
            throw new IllegalArgumentException("Graph projection readiness is incomplete");
        }
        provider = provider.strip();
        boolean active = status == GraphProjectionReadinessStatus.BUILDING
                || status == GraphProjectionReadinessStatus.REPAIRING
                || status == GraphProjectionReadinessStatus.CLEARING;
        boolean operationAbsent = operationKind == null && operationOwner == null
                && operationSourceFingerprint == null && operationStartedAt == null;
        boolean operationComplete = operationKind != null && operationOwner != null
                && !operationOwner.isBlank() && operationSourceFingerprint != null
                && operationSourceFingerprint.matches("[0-9a-f]{64}")
                && operationStartedAt != null && !operationStartedAt.isBlank();
        if (active != operationComplete || !active && !operationAbsent
                || active && operationKind.activeStatus() != status) {
            throw new IllegalArgumentException("Graph projection operation state is inconsistent");
        }
        boolean appliedProofComplete = appliedGeneration > 0 && sourceFingerprint != null
                && sourceFingerprint.matches("[0-9a-f]{64}") && snapshotToken != null
                && snapshotToken.matches("[0-9a-f]{64}");
        if ((appliedGeneration > 0) != appliedProofComplete
                || status == GraphProjectionReadinessStatus.READY && !appliedProofComplete
                || status == GraphProjectionReadinessStatus.DEGRADED && !appliedProofComplete
                || status == GraphProjectionReadinessStatus.FAILED && appliedProofComplete) {
            throw new IllegalArgumentException("Graph projection applied proof is inconsistent");
        }
    }

    public GraphProjectionSnapshot appliedSnapshot() {
        if (appliedGeneration < 1 || sourceFingerprint == null || snapshotToken == null) {
            return null;
        }
        return new GraphProjectionSnapshot(workspace, projectionVersion, appliedGeneration,
                sourceFingerprint, snapshotToken);
    }

    public GraphProjectionSnapshot targetSnapshot() {
        if (targetGeneration < 1 || operationSourceFingerprint == null
                || operationKind == GraphProjectionOperationKind.CLEAR) {
            return null;
        }
        return GraphProjectionSnapshot.fromProof(workspace, projectionVersion, targetGeneration,
                operationSourceFingerprint);
    }

    public GraphProjectionOperation activeOperation() {
        if (operationKind == null) {
            return null;
        }
        return new GraphProjectionOperation(workspace, provider, projectionVersion, operationKind,
                targetGeneration, operationOwner, operationSourceFingerprint, operationStartedAt,
                appliedSnapshot());
    }
}
