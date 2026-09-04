package org.km.llmwiki.graph;

/** Immutable workspace-scoped marker for one successful projection generation. */
public record GraphProjectionSnapshot(GraphWorkspaceScope workspace,
                                      GraphProjectionVersion projectionVersion,
                                      long generation, String sourceFingerprint,
                                      String snapshotToken) {

    public GraphProjectionSnapshot {
        if (workspace == null || projectionVersion == null || generation < 1
                || !isSha256(sourceFingerprint) || !isSha256(snapshotToken)) {
            throw new IllegalArgumentException("Graph projection snapshot is incomplete");
        }
    }

    public static GraphProjectionSnapshot of(GraphProjectionInput input, long generation) {
        if (input == null || generation < 1) {
            throw new IllegalArgumentException("Graph projection input and positive generation are required");
        }
        String token = GraphIdentityCodec.digest("snapshot", Long.toString(input.workspace().id()),
                input.projectionVersion().value(), Long.toString(generation), input.sourceFingerprint());
        return new GraphProjectionSnapshot(input.workspace(), input.projectionVersion(), generation,
                input.sourceFingerprint(), token);
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
