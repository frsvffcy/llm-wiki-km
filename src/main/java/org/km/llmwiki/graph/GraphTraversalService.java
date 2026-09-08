package org.km.llmwiki.graph;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Executes traversal between two complete lifecycle checks without holding a SQLite transaction
 * across backend I/O. The second check is the serving linearization point for materialized data.
 */
public final class GraphTraversalService {

    private final GraphProjectionReadinessReader readinessReader;
    private final GraphTraversalBackendFactory backendFactory;

    public GraphTraversalService(GraphProjectionReadinessReader readinessReader,
                                 GraphTraversalBackendFactory backendFactory) {
        if (readinessReader == null) {
            throw new IllegalArgumentException("Graph projection lifecycle is required");
        }
        this.readinessReader = readinessReader;
        this.backendFactory = backendFactory;
    }

    public GraphTraversalResult traverse(GraphTraversalQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("Graph traversal query is required");
        }
        GraphProjectionSnapshot expected = query.expectedSnapshot();
        GraphProjectionVerification before = readinessReader.readiness(query.workspace());
        GraphSnapshotCurrentness.requireCurrent(before, expected, false);
        if (backendFactory == null) {
            throw new GraphProjectionException(GraphProjectionFailureType.CAPABILITY_UNAVAILABLE);
        }
        if (!expected.projectionVersion().equals(backendFactory.projectionVersion())) {
            throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
        }

        GraphTraversalResult materialized;
        Optional<GraphTraversalBackend> existing = backendFactory.openTraversal(query.workspace());
        if (existing.isEmpty()) {
            throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_NOT_READY);
        }
        try (GraphTraversalBackend backend = existing.orElseThrow()) {
            requireProof(backend.readProof(query.workspace()), expected);
            materialized = backend.traverse(query);
            requireProof(backend.readProof(query.workspace()), expected);
        }
        validateResult(query, materialized);

        GraphProjectionVerification after = readinessReader.readiness(query.workspace());
        GraphSnapshotCurrentness.requireCurrent(after, expected, true);
        return materialized;
    }

    private static void requireProof(GraphProjectionBackendProof proof,
                                     GraphProjectionSnapshot expected) {
        if (proof == null || !expected.workspace().equals(proof.workspace())) {
            throw corruptProof();
        }
        GraphProjectionSnapshot actual = proof.currentSnapshot();
        if (actual == null || proof.clearedSnapshot() != null) {
            throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE);
        }
        if (!expected.projectionVersion().equals(actual.projectionVersion())) {
            throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
        }
        if (expected.generation() != actual.generation()) {
            throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE);
        }
        if (!expected.equals(actual)) {
            throw corruptProof();
        }
    }

    private static void validateResult(GraphTraversalQuery query, GraphTraversalResult result) {
        if (result == null || !query.expectedSnapshot().equals(result.snapshot())
                || result.visitedNodeCount() < query.seeds().size()
                || result.visitedNodeCount() > query.bounds().maxVisitedNodes()
                || result.visitedEdgeCount() > query.bounds().maxVisitedEdges()
                || result.candidates().size() > query.bounds().candidateLimit()) {
            throw corruptProof();
        }
        List<GraphTraversalCandidate> sorted = result.candidates().stream()
                .sorted(query.ordering().candidateComparator()).toList();
        if (!sorted.equals(result.candidates())) {
            throw corruptProof();
        }
        Set<GraphEntityIdentity> seeds = new HashSet<>(query.seeds());
        Set<GraphEntityIdentity> candidates = new HashSet<>();
        Set<GraphRelationIdentity> pathRelations = new HashSet<>();
        for (GraphTraversalCandidate candidate : result.candidates()) {
            GraphEntity entity = candidate.entity();
            if (!seeds.contains(candidate.seed())
                    || seeds.contains(entity.identity())
                    || !candidates.add(entity.identity())
                    || candidate.depth() > query.bounds().maxDepth()
                    || !inWorkspace(query.workspace(), entity.identity(),
                    entity.provenance())
                    || !query.expectedSnapshot().projectionVersion()
                    .equals(entity.projectionVersion())) {
                throw corruptProof();
            }
            for (GraphRelation relation : candidate.path()) {
                if (!query.allowedRelationTypes().contains(relation.type())
                        || !query.expectedSnapshot().projectionVersion()
                        .equals(relation.projectionVersion())
                        || !query.workspace().equals(relation.identity().workspace())
                        || !inWorkspace(query.workspace(), relation.source(),
                        relation.provenance())
                        || !query.workspace().equals(relation.target().workspace())) {
                    throw corruptProof();
                }
                pathRelations.add(relation.identity());
            }
        }
        if (result.visitedNodeCount() < seeds.size() + candidates.size()
                || result.visitedEdgeCount() < pathRelations.size()) {
            throw corruptProof();
        }
    }

    private static boolean inWorkspace(GraphWorkspaceScope workspace,
                                       GraphEntityIdentity identity,
                                       GraphProvenance provenance) {
        return workspace.equals(identity.workspace())
                && workspace.equals(provenance.authority().workspace());
    }

    private static GraphProjectionException corruptProof() {
        return new GraphProjectionException(GraphProjectionFailureType.PROJECTION_CORRUPT);
    }
}
