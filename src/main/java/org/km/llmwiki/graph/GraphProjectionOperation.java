package org.km.llmwiki.graph;

/** SQLite-reserved monotonic generation and compare-and-set ownership token. */
public record GraphProjectionOperation(GraphWorkspaceScope workspace, String provider,
                                       GraphProjectionVersion projectionVersion,
                                       GraphProjectionOperationKind kind, long generation,
                                       String ownerToken, String sourceFingerprint,
                                       String startedAt,
                                       GraphProjectionSnapshot expectedAppliedSnapshot) {
    public GraphProjectionOperation {
        if (workspace == null || provider == null || provider.isBlank()
                || projectionVersion == null || kind == null || generation < 1
                || ownerToken == null || ownerToken.isBlank()
                || sourceFingerprint == null || !sourceFingerprint.matches("[0-9a-f]{64}")
                || startedAt == null || startedAt.isBlank()) {
            throw new IllegalArgumentException("Graph projection operation is incomplete");
        }
        provider = provider.strip();
        if (expectedAppliedSnapshot != null
                && (!workspace.equals(expectedAppliedSnapshot.workspace())
                || !projectionVersion.equals(expectedAppliedSnapshot.projectionVersion()))) {
            throw new IllegalArgumentException("Graph projection operation crosses workspace boundary");
        }
        if (kind == GraphProjectionOperationKind.CLEAR && expectedAppliedSnapshot == null) {
            throw new IllegalArgumentException("Graph projection clear requires an applied proof");
        }
    }

    public GraphProjectionSnapshot targetSnapshot() {
        if (kind == GraphProjectionOperationKind.CLEAR) {
            return null;
        }
        return GraphProjectionSnapshot.fromProof(workspace, projectionVersion, generation,
                sourceFingerprint);
    }
}
