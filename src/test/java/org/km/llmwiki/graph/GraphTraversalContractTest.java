package org.km.llmwiki.graph;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class GraphTraversalContractTest {

    private static final GraphWorkspaceScope WORKSPACE = new GraphWorkspaceScope(41);
    private static final GraphProjectionVersion VERSION = GraphProjectionVersion.initial();
    private static final GraphProjectionSnapshot SNAPSHOT = GraphProjectionSnapshot.fromProof(
            WORKSPACE, VERSION, 7, "a".repeat(64));

    @Test
    void everyCallerLimitIsPositiveAndCannotExceedItsHardCap() {
        assertInvalidBounds(() -> new GraphTraversalBounds(0, 1, 1, 1, 1, 1));
        assertInvalidBounds(() -> new GraphTraversalBounds(1,
                GraphTraversalBounds.HARD_MAX_PER_NODE_FAN_OUT + 1, 1, 1, 1, 1));
        assertInvalidBounds(() -> new GraphTraversalBounds(1, 1,
                GraphTraversalBounds.HARD_MAX_PER_HOP_FAN_OUT + 1, 1, 1, 1));
        assertInvalidBounds(() -> new GraphTraversalBounds(1, 1, 1,
                GraphTraversalBounds.HARD_MAX_VISITED_NODES + 1, 1, 1));
        assertInvalidBounds(() -> new GraphTraversalBounds(1, 1, 1, 1,
                GraphTraversalBounds.HARD_MAX_VISITED_EDGES + 1, 1));
        assertInvalidBounds(() -> new GraphTraversalBounds(1, 1, 1, 1, 1,
                GraphTraversalBounds.HARD_MAX_CANDIDATES + 1));
        assertInvalidBounds(() -> new GraphTraversalBounds(
                GraphTraversalBounds.HARD_MAX_DEPTH + 1, 1, 1, 1, 1, 1));
    }

    @Test
    void queryCanonicalizesSeedOrderAndRejectsDuplicateOrCrossWorkspaceSeeds() {
        GraphEntityIdentity alpha = identity(WORKSPACE, "alpha");
        GraphEntityIdentity beta = identity(WORKSPACE, "beta");
        GraphTraversalQuery query = query(List.of(beta, alpha), bounds(4));

        assertThat(query.seeds()).isSortedAccordingTo(
                java.util.Comparator.comparing(GraphEntityIdentity::stableId));
        assertInvalidBounds(() -> query(List.of(alpha, alpha), bounds(4)));
        assertFailure(GraphProjectionFailureType.CROSS_WORKSPACE,
                () -> query(List.of(identity(new GraphWorkspaceScope(42), "foreign")), bounds(4)));
    }

    @Test
    void candidateRequiresAContiguousPathFromTheDeclaredSeed() {
        GraphEntity source = entity("source");
        GraphEntity middle = entity("middle");
        GraphEntity target = entity("target");
        GraphRelation first = relation(source, middle);
        GraphRelation second = relation(middle, target);

        GraphTraversalCandidate candidate = new GraphTraversalCandidate(source.identity(), target,
                2, List.of(first, second));

        assertThat(candidate.depth()).isEqualTo(2);
        assertThatThrownBy(() -> new GraphTraversalCandidate(source.identity(), target,
                2, List.of(second, first))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderingAndSnapshotTokenAreApplicationOwnedAndDeterministic() {
        GraphEntity seed = entity("seed");
        GraphEntity alpha = entity("alpha");
        GraphEntity beta = entity("beta");
        GraphRelation linksToBeta = relation(seed, beta);
        GraphRelation linksToAlpha = relation(seed, alpha);
        GraphRelation containsBeta = new GraphRelation(GraphRelationIdentity.of(seed.identity(),
                GraphRelationType.CONTAINS, beta.identity()), seed.identity(),
                GraphRelationType.CONTAINS, beta.identity(), seed.provenance(),
                GraphMetadata.empty(), VERSION);
        GraphTraversalCandidate alphaCandidate = new GraphTraversalCandidate(seed.identity(),
                alpha, 1, List.of(linksToAlpha));
        GraphTraversalCandidate betaCandidate = new GraphTraversalCandidate(seed.identity(),
                beta, 1, List.of(linksToBeta));

        assertThat(List.of(linksToBeta, linksToAlpha, containsBeta).stream()
                .sorted(GraphTraversalOrdering.DEPTH_SEED_ENTITY_PATH_V1
                        .outgoingRelationComparator())
                .toList()).containsExactly(containsBeta, linksToAlpha, linksToBeta);

        assertThat(List.of(betaCandidate, alphaCandidate).stream()
                .sorted(GraphTraversalOrdering.DEPTH_SEED_ENTITY_PATH_V1.candidateComparator())
                .toList()).extracting(candidate -> candidate.entity().identity().stableId())
                .containsExactlyElementsOf(List.of(alpha, beta).stream()
                        .map(candidate -> candidate.identity().stableId()).sorted().toList());

        GraphProjectionSnapshot changedFingerprint = GraphProjectionSnapshot.fromProof(
                WORKSPACE, VERSION, SNAPSHOT.generation(), "b".repeat(64));
        assertThat(changedFingerprint.snapshotToken()).isNotEqualTo(SNAPSHOT.snapshotToken());
        assertThat(query(List.of(seed.identity()), bounds(4)).expectedSnapshot())
                .isEqualTo(SNAPSHOT);
    }

    private static GraphTraversalQuery query(List<GraphEntityIdentity> seeds,
                                             GraphTraversalBounds bounds) {
        return new GraphTraversalQuery(WORKSPACE, seeds, Set.of(GraphRelationType.LINKS_TO),
                bounds, GraphTraversalOrdering.DEPTH_SEED_ENTITY_PATH_V1, SNAPSHOT);
    }

    private static GraphTraversalBounds bounds(int maxVisitedNodes) {
        return new GraphTraversalBounds(2, 4, 8, maxVisitedNodes, 16, 8);
    }

    private static GraphEntity entity(String id) {
        GraphAuthorityReference authority = new GraphAuthorityReference(WORKSPACE,
                GraphAuthorityKind.WIKI_PAGE, id);
        return new GraphEntity(GraphEntityIdentity.fromAuthority(authority,
                GraphEntityType.WIKI_PAGE), id,
                new GraphProvenance(authority, GraphFreshness.revision(1),
                        GraphAuthorityEligibility.ELIGIBLE, GraphMetadata.empty()),
                GraphMetadata.empty(), VERSION);
    }

    private static GraphEntityIdentity identity(GraphWorkspaceScope workspace, String id) {
        return GraphEntityIdentity.fromAuthority(new GraphAuthorityReference(workspace,
                GraphAuthorityKind.WIKI_PAGE, id), GraphEntityType.WIKI_PAGE);
    }

    private static GraphRelation relation(GraphEntity source, GraphEntity target) {
        return new GraphRelation(GraphRelationIdentity.of(source.identity(),
                GraphRelationType.LINKS_TO, target.identity()), source.identity(),
                GraphRelationType.LINKS_TO, target.identity(), source.provenance(),
                GraphMetadata.empty(), VERSION);
    }

    private static void assertInvalidBounds(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertFailure(GraphProjectionFailureType.INVALID_TRAVERSAL_BOUNDS, callable);
    }

    private static void assertFailure(GraphProjectionFailureType expected,
                                      org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOf(GraphProjectionException.class)
                .extracting(failure -> ((GraphProjectionException) failure).failureType())
                .isEqualTo(expected);
    }
}
