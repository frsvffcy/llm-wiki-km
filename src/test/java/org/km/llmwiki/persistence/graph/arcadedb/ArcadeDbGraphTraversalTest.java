package org.km.llmwiki.persistence.graph.arcadedb;

import com.arcadedb.database.DatabaseFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.GraphEntity;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionWriteContext;
import org.km.llmwiki.graph.GraphRelation;
import org.km.llmwiki.graph.GraphRelationType;
import org.km.llmwiki.graph.GraphTraversalBounds;
import org.km.llmwiki.graph.GraphTraversalLimit;
import org.km.llmwiki.graph.GraphTraversalOrdering;
import org.km.llmwiki.graph.GraphTraversalQuery;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class ArcadeDbGraphTraversalTest {

    @TempDir
    Path tempDir;

    @Test
    void traversesDeterministicallyAcrossRestartWithoutFollowingCycles() {
        GraphEntity seed = page("seed");
        GraphEntity alpha = page("alpha");
        GraphEntity beta = page("beta");
        GraphEntity gamma = page("gamma");
        List<GraphRelation> relations = List.of(
                relation(beta, GraphRelationType.MENTIONS, gamma),
                relation(seed, GraphRelationType.RELATED_TO, beta),
                relation(gamma, GraphRelationType.LINKS_TO, seed),
                relation(seed, GraphRelationType.LINKS_TO, alpha));
        GraphProjectionWriteContext context = context(
                List.of(seed, alpha, beta, gamma), relations);
        GraphTraversalQuery query = query(context, List.of(seed),
                new GraphTraversalBounds(3, 8, 16, 16, 32, 16));
        Path path = tempDir.resolve("deterministic-restart");

        List<?> beforeRestart;
        try (var writer = new ArcadeDbGraphProjectionWriter(path)) {
            stageAndPublish(writer, context, List.of(gamma, beta, alpha, seed), relations);
            var result = writer.traverse(query);
            beforeRestart = result.candidates();
            assertThat(result.candidates()).hasSize(3)
                    .isSortedAccordingTo(query.ordering().candidateComparator());
            assertThat(result.candidates()).extracting(candidate -> candidate.entity().identity())
                    .doesNotContain(seed.identity());
            assertThat(result.visitedNodeCount()).isEqualTo(4);
            assertThat(result.visitedEdgeCount()).isEqualTo(4);
        }

        try (var reopened = new ArcadeDbGraphProjectionWriter(path)) {
            assertThat(reopened.traverse(query).candidates()).isEqualTo(beforeRestart);
        }
    }

    @Test
    void reportsEveryHardStopAndUsesApplicationOwnedTargetOrdering() {
        GraphEntity seed = page("fan-out-seed");
        List<GraphEntity> targets = List.of(page("zeta"), page("alpha"), page("mu"));
        List<GraphRelation> relations = targets.stream()
                .map(target -> relation(seed, GraphRelationType.LINKS_TO, target)).toList();
        GraphProjectionWriteContext context = context(
                List.of(seed, targets.get(0), targets.get(1), targets.get(2)), relations);
        Path path = tempDir.resolve("limits");

        try (var writer = new ArcadeDbGraphProjectionWriter(path)) {
            stageAndPublish(writer, context,
                    List.of(targets.get(2), seed, targets.get(0), targets.get(1)),
                    List.of(relations.get(2), relations.get(0), relations.get(1)));

            var perNode = writer.traverse(query(context, List.of(seed),
                    new GraphTraversalBounds(1, 2, 8, 8, 8, 8)));
            assertThat(perNode.candidates()).hasSize(2);
            assertThat(perNode.reachedLimits()).contains(GraphTraversalLimit.PER_NODE_FAN_OUT,
                    GraphTraversalLimit.DEPTH);
            assertThat(perNode.candidates()).extracting(candidate ->
                            candidate.entity().identity().stableId())
                    .containsExactlyElementsOf(targets.stream().map(target ->
                                    target.identity().stableId()).sorted().limit(2).toList());

            assertLimit(writer, context, seed,
                    new GraphTraversalBounds(1, 8, 1, 8, 8, 8),
                    GraphTraversalLimit.PER_HOP_FAN_OUT);
            assertLimit(writer, context, seed,
                    new GraphTraversalBounds(1, 8, 8, 8, 1, 8),
                    GraphTraversalLimit.VISITED_EDGES);
            assertLimit(writer, context, seed,
                    new GraphTraversalBounds(1, 8, 8, 2, 8, 8),
                    GraphTraversalLimit.VISITED_NODES);
            assertLimit(writer, context, seed,
                    new GraphTraversalBounds(1, 8, 8, 8, 8, 1),
                    GraphTraversalLimit.CANDIDATES);
        }
    }

    @Test
    void fanOutSelectionFollowsApplicationRelationOrderingAcrossTypes() {
        GraphEntity seed = page("type-order-seed");
        GraphEntity containsTarget = page("type-order-contains");
        GraphEntity linksTarget = page("type-order-links");
        GraphEntity relatedTarget = page("type-order-related");
        GraphRelation contains = relation(seed, GraphRelationType.CONTAINS, containsTarget);
        GraphRelation links = relation(seed, GraphRelationType.LINKS_TO, linksTarget);
        GraphRelation related = relation(seed, GraphRelationType.RELATED_TO, relatedTarget);
        GraphProjectionWriteContext context = context(
                List.of(seed, containsTarget, linksTarget, relatedTarget),
                List.of(related, links, contains));

        try (var writer = new ArcadeDbGraphProjectionWriter(tempDir.resolve("type-order"))) {
            stageAndPublish(writer, context,
                    List.of(relatedTarget, linksTarget, containsTarget, seed),
                    List.of(related, links, contains));

            var result = writer.traverse(query(context, List.of(seed),
                    new GraphTraversalBounds(1, 1, 8, 8, 8, 8)));

            assertThat(result.candidates()).singleElement()
                    .satisfies(candidate -> assertThat(candidate.path().getFirst().type())
                            .isEqualTo(GraphRelationType.CONTAINS));
            assertThat(result.reachedLimits()).contains(GraphTraversalLimit.PER_NODE_FAN_OUT);
        }
    }

    @Test
    void compositePrefixKeepsSameTypeRelationsScopedToTheRequestedSource() {
        GraphEntity seed = page("prefix-seed");
        GraphEntity otherSource = page("prefix-other-source");
        GraphEntity expectedTarget = page("prefix-expected-target");
        GraphEntity foreignTarget = page("prefix-foreign-target");
        GraphRelation expected = relation(seed, GraphRelationType.LINKS_TO, expectedTarget);
        GraphRelation foreign = relation(otherSource, GraphRelationType.LINKS_TO, foreignTarget);
        GraphProjectionWriteContext context = context(
                List.of(seed, otherSource, expectedTarget, foreignTarget),
                List.of(expected, foreign));

        try (var writer = new ArcadeDbGraphProjectionWriter(tempDir.resolve("prefix-scope"))) {
            stageAndPublish(writer, context,
                    List.of(foreignTarget, expectedTarget, otherSource, seed),
                    List.of(foreign, expected));

            var result = writer.traverse(query(context, List.of(seed),
                    new GraphTraversalBounds(1, 8, 8, 8, 8, 8)));

            assertThat(result.candidates()).extracting(candidate ->
                            candidate.entity().identity().stableId())
                    .containsExactly(expectedTarget.identity().stableId());
            assertThat(result.visitedEdgeCount()).isEqualTo(1);
        }
    }

    @Test
    void depthLimitMissingSeedAndWorkspaceBoundaryFailClosed() {
        GraphEntity seed = page("depth-seed");
        GraphEntity middle = page("depth-middle");
        GraphEntity end = page("depth-end");
        List<GraphRelation> relations = List.of(
                relation(seed, GraphRelationType.LINKS_TO, middle),
                relation(middle, GraphRelationType.LINKS_TO, end));
        GraphProjectionWriteContext context = context(List.of(seed, middle, end), relations);

        try (var writer = new ArcadeDbGraphProjectionWriter(tempDir.resolve("depth"))) {
            stageAndPublish(writer, context, List.of(seed, middle, end), relations);
            var bounded = writer.traverse(query(context, List.of(seed),
                    new GraphTraversalBounds(1, 8, 8, 8, 8, 8)));
            assertThat(bounded.candidates()).hasSize(1);
            assertThat(bounded.reachedLimits()).contains(GraphTraversalLimit.DEPTH);

            GraphEntity absent = page("absent");
            var missing = writer.traverse(query(context, List.of(absent),
                    new GraphTraversalBounds(2, 8, 8, 8, 8, 8)));
            assertThat(missing.candidates()).isEmpty();
            assertThat(missing.visitedNodeCount()).isEqualTo(1);
        }

        try (var backend = new ArcadeDbGraphProjectionBackend(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, tempDir.resolve("depth"), false, () -> { })) {
            GraphEntity foreign = ArcadeDbGraphProjectionFixtures.page(
                    ArcadeDbGraphProjectionFixtures.OTHER_WORKSPACE, "foreign", "外部頁");
            GraphTraversalQuery foreignQuery = new GraphTraversalQuery(
                    ArcadeDbGraphProjectionFixtures.OTHER_WORKSPACE, List.of(foreign.identity()),
                    Set.of(GraphRelationType.LINKS_TO),
                    new GraphTraversalBounds(1, 1, 1, 1, 1, 1),
                    GraphTraversalOrdering.DEPTH_SEED_ENTITY_PATH_V1,
                    org.km.llmwiki.graph.GraphProjectionSnapshot.fromProof(
                            ArcadeDbGraphProjectionFixtures.OTHER_WORKSPACE,
                            ArcadeDbGraphProjectionFixtures.VERSION, 1,
                            context.sourceFingerprint()));
            assertThatThrownBy(() -> backend.traverse(foreignQuery))
                    .isInstanceOf(GraphProjectionException.class)
                    .extracting(failure -> ((GraphProjectionException) failure).failureType())
                    .isEqualTo(GraphProjectionFailureType.CROSS_WORKSPACE);
        }
    }

    @Test
    void malformedRelationProofAndEndpointIdentityFailClosedAsCorrupt() {
        GraphEntity seed = page("corrupt-seed");
        GraphEntity target = page("corrupt-target");
        GraphRelation relation = relation(seed, GraphRelationType.LINKS_TO, target);
        GraphProjectionWriteContext context = context(List.of(seed, target), List.of(relation));

        Path proofPath = tempDir.resolve("corrupt-row-proof");
        try (var writer = new ArcadeDbGraphProjectionWriter(proofPath)) {
            stageAndPublish(writer, context, List.of(seed, target), List.of(relation));
        }
        mutateFirstRelation(proofPath, "snapshot_token", "invalid-token");
        assertCorrupt(proofPath, query(context, List.of(seed),
                new GraphTraversalBounds(1, 8, 8, 8, 8, 8)));

        Path endpointPath = tempDir.resolve("corrupt-endpoint");
        try (var writer = new ArcadeDbGraphProjectionWriter(endpointPath)) {
            stageAndPublish(writer, context, List.of(seed, target), List.of(relation));
        }
        mutateFirstRelation(endpointPath, "target_stable_id", seed.identity().stableId());
        assertCorrupt(endpointPath, query(context, List.of(seed),
                new GraphTraversalBounds(1, 8, 8, 8, 8, 8)));
    }

    private static void assertLimit(ArcadeDbGraphProjectionWriter writer,
                                    GraphProjectionWriteContext context,
                                    GraphEntity seed, GraphTraversalBounds bounds,
                                    GraphTraversalLimit expected) {
        var result = writer.traverse(query(context, List.of(seed), bounds));
        assertThat(result.reachedLimits()).contains(expected);
    }

    private static void assertCorrupt(Path path, GraphTraversalQuery query) {
        try (var writer = new ArcadeDbGraphProjectionWriter(path)) {
            assertThatThrownBy(() -> writer.traverse(query))
                    .isInstanceOf(GraphProjectionException.class)
                    .extracting(failure -> ((GraphProjectionException) failure).failureType())
                    .isEqualTo(GraphProjectionFailureType.PROJECTION_CORRUPT);
        }
    }

    private static GraphEntity page(String id) {
        return ArcadeDbGraphProjectionFixtures.page(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, id, id);
    }

    private static GraphRelation relation(GraphEntity source, GraphRelationType type,
                                          GraphEntity target) {
        return GraphRelation.of(source.identity(), type, target.identity(), source.provenance(),
                org.km.llmwiki.graph.GraphMetadata.empty());
    }

    private static GraphProjectionWriteContext context(List<GraphEntity> entities,
                                                       List<GraphRelation> relations) {
        return GraphProjectionWriteContext.of(new GraphProjectionInput(
                ArcadeDbGraphProjectionFixtures.WORKSPACE,
                ArcadeDbGraphProjectionFixtures.VERSION, entities, relations), 1);
    }

    private static GraphTraversalQuery query(GraphProjectionWriteContext context,
                                             List<GraphEntity> seeds,
                                             GraphTraversalBounds bounds) {
        return new GraphTraversalQuery(context.workspace(),
                seeds.stream().map(GraphEntity::identity).toList(),
                EnumSet.allOf(GraphRelationType.class), bounds,
                GraphTraversalOrdering.DEPTH_SEED_ENTITY_PATH_V1, context.snapshot());
    }

    private static void stageAndPublish(ArcadeDbGraphProjectionWriter writer,
                                        GraphProjectionWriteContext context,
                                        List<GraphEntity> entities,
                                        List<GraphRelation> relations) {
        entities.forEach(entity -> writer.upsertEntity(context, entity));
        relations.forEach(relation -> writer.upsertRelation(context, relation));
        writer.publish(context);
    }

    private static void mutateFirstRelation(Path path, String property, Object value) {
        try (DatabaseFactory factory = new DatabaseFactory(path.toString()).setAutoTransaction(false)) {
            var database = factory.open().setReadYourWrites(true);
            try {
                database.transaction(() -> {
                    var records = database.iterateType(
                            ArcadeDbGraphProjectionWriter.RELATION_TYPE, false);
                    records.next().asDocument(true).modify().set(property, value).save();
                });
            } finally {
                database.close();
            }
        }
    }
}
