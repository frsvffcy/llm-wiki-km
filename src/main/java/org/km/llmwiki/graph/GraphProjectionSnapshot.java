package org.km.llmwiki.graph;

/** Immutable workspace-scoped marker for one successful projection generation. */
public record GraphProjectionSnapshot(GraphWorkspaceScope workspace,
                                      GraphProjectionVersion projectionVersion,
                                      long generation, String sourceFingerprint,
                                      String snapshotToken) {

    /** Application-owned version for the deterministic snapshot proof encoding. */
    public static final String TOKEN_VERSION = "graph-projection-snapshot-v1";

    public GraphProjectionSnapshot {
        if (workspace == null || projectionVersion == null || generation < 1
                || !isSha256(sourceFingerprint) || !isSha256(snapshotToken)) {
            throw new IllegalArgumentException("Graph projection snapshot is incomplete");
        }
        if (!expectedToken(workspace, projectionVersion, generation, sourceFingerprint)
                .equals(snapshotToken)) {
            throw new IllegalArgumentException("Graph projection snapshot token does not match its fields");
        }
    }

    public static GraphProjectionSnapshot of(GraphProjectionInput input, long generation) {
        if (input == null || generation < 1) {
            throw new IllegalArgumentException("Graph projection input and positive generation are required");
        }
        String token = expectedToken(input.workspace(), input.projectionVersion(), generation,
                input.sourceFingerprint());
        return new GraphProjectionSnapshot(input.workspace(), input.projectionVersion(), generation,
                input.sourceFingerprint(), token);
    }

    /** Returns the application-owned proof expected for the supplied snapshot fields. */
    static String expectedToken(GraphWorkspaceScope workspace, GraphProjectionVersion projectionVersion,
                                long generation, String sourceFingerprint) {
        return GraphIdentityCodec.digest(TOKEN_VERSION, Long.toString(requireWorkspace(workspace)),
                requireVersion(projectionVersion), Long.toString(requireGeneration(generation)),
                requireFingerprint(sourceFingerprint));
    }

    private static long requireGeneration(long generation) {
        if (generation < 1) {
            throw new IllegalArgumentException("Graph projection generation must be positive");
        }
        return generation;
    }

    private static long requireWorkspace(GraphWorkspaceScope workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("Graph projection workspace is required");
        }
        return workspace.id();
    }

    private static String requireVersion(GraphProjectionVersion projectionVersion) {
        if (projectionVersion == null) {
            throw new IllegalArgumentException("Graph projection version is required");
        }
        return projectionVersion.value();
    }

    private static String requireFingerprint(String sourceFingerprint) {
        if (!isSha256(sourceFingerprint)) {
            throw new IllegalArgumentException("Graph projection source fingerprint is invalid");
        }
        return sourceFingerprint;
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
