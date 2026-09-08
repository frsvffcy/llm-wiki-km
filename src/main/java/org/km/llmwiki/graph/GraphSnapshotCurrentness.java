package org.km.llmwiki.graph;

/**
 * Shared query-time snapshot currentness contract for every graph serving surface.
 *
 * <p>{@link GraphTraversalService} applies this between two lifecycle checks so materialized
 * topology has a serving linearization point; evidence admission applies the same contract when a
 * returned traversal result is converted into canonical {@code EvidenceItem}s, closing the
 * consumption window after traversal returns. No SQLite transaction is held across backend or
 * canonical reads by either caller.
 */
public final class GraphSnapshotCurrentness {

    private GraphSnapshotCurrentness() {
    }

    /** Fails closed unless the control plane still proves exactly the expected snapshot. */
    public static void requireCurrent(GraphProjectionVerification verification,
                                      GraphProjectionSnapshot expected,
                                      boolean materialized) {
        if (verification == null || !expected.workspace().equals(verification.workspace())) {
            throw corruptProof();
        }
        if (!verification.ready()) {
            GraphProjectionFailureType type = switch (verification.status()) {
                case DISABLED -> GraphProjectionFailureType.CAPABILITY_DISABLED;
                case NOT_CONFIGURED -> GraphProjectionFailureType.CONFIGURATION_INVALID;
                case STALE -> GraphProjectionFailureType.PROJECTION_STALE;
                case PROJECTION_INCOMPATIBLE ->
                        GraphProjectionFailureType.PROJECTION_INCOMPATIBLE;
                case BACKEND_UNAVAILABLE -> verification.failure() == null
                        ? GraphProjectionFailureType.BACKEND_FAILURE
                        : verification.failure().type();
                default -> verification.failure() == null
                        ? GraphProjectionFailureType.PROJECTION_NOT_READY
                        : verification.failure().type();
            };
            if (materialized && type == GraphProjectionFailureType.PROJECTION_NOT_READY) {
                type = GraphProjectionFailureType.PROJECTION_STALE;
            }
            throw new GraphProjectionException(type);
        }
        GraphProjectionSnapshot actual = verification.controlPlane() == null
                ? null : verification.controlPlane().appliedSnapshot();
        if (actual == null || expected.generation() != actual.generation()) {
            throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE);
        }
        if (!expected.projectionVersion().equals(actual.projectionVersion())) {
            throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
        }
        if (!expected.equals(actual)) {
            throw corruptProof();
        }
    }

    private static GraphProjectionException corruptProof() {
        return new GraphProjectionException(GraphProjectionFailureType.PROJECTION_CORRUPT);
    }
}
