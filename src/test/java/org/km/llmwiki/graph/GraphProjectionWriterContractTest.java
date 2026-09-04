package org.km.llmwiki.graph;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("contract")
class GraphProjectionWriterContractTest {

    private static final String HASH = "c".repeat(64);

    @Test
    void olderReconciliationCannotRemoveNewerGenerationRows() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(31);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphEntity second = entity(workspace, "second", "Second", version);
        GraphRelation firstRelation = relation(first, second, GraphRelationType.LINKS_TO);
        GraphProjectionInput generationA = new GraphProjectionInput(workspace, version,
                List.of(first), List.of());
        GraphProjectionInput generationB = new GraphProjectionInput(workspace, version,
                List.of(first, second), List.of(firstRelation));
        GraphProjectionReconciliation reconciliationA = reconciliation(generationA, 10);
        GraphProjectionReconciliation reconciliationB = reconciliation(generationB, 11);
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();

        writer.publish(generationA, 10);
        writer.publish(generationB, 11);

        GraphProjectionCleanupResult stale = writer.removeStale(reconciliationA);

        assertThat(stale.status()).isEqualTo(GraphProjectionCleanupStatus.SUPERSEDED);
        assertThat(stale.removedEntities()).isZero();
        assertThat(stale.removedRelations()).isZero();
        assertThat(writer.hasEntity(second.identity())).isTrue();
        assertThat(writer.hasRelation(firstRelation.identity())).isTrue();
        assertThat(writer.removeStale(reconciliationB).status())
                .isEqualTo(GraphProjectionCleanupStatus.NO_OP);
    }

    @Test
    void sameGenerationCleanupIsDeterministicAndIdempotent() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(32);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphEntity second = entity(workspace, "second", "Second", version);
        GraphRelation relation = relation(first, second, GraphRelationType.RELATED_TO);
        GraphProjectionInput initial = new GraphProjectionInput(workspace, version,
                List.of(first, second), List.of(relation));
        GraphProjectionInput reduced = new GraphProjectionInput(workspace, version,
                List.of(first), List.of());
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        writer.publish(initial, 7);
        writer.publish(reduced, 8);

        GraphProjectionCleanupResult applied = writer.removeStale(reconciliation(reduced, 8));
        GraphProjectionCleanupResult repeated = writer.removeStale(reconciliation(reduced, 8));

        assertThat(applied.status()).isEqualTo(GraphProjectionCleanupStatus.APPLIED);
        assertThat(applied.removedEntities()).isEqualTo(1);
        assertThat(applied.removedRelations()).isEqualTo(1);
        assertThat(repeated.status()).isEqualTo(GraphProjectionCleanupStatus.NO_OP);
        assertThat(repeated.removedEntities()).isZero();
        assertThat(repeated.removedRelations()).isZero();
        assertThat(writer.hasEntity(first.identity())).isTrue();
        assertThat(writer.hasEntity(second.identity())).isFalse();
        assertThat(writer.hasRelation(relation.identity())).isFalse();
    }

    @Test
    void sameGenerationProofMismatchFailsClosedWithoutCleanup() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(33);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphEntity second = entity(workspace, "second", "Second", version);
        GraphProjectionInput currentInput = new GraphProjectionInput(workspace, version,
                List.of(first), List.of());
        GraphProjectionInput conflictingInput = new GraphProjectionInput(workspace, version,
                List.of(second), List.of());
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        writer.publish(currentInput, 12);

        assertThatThrownBy(() -> writer.removeStale(reconciliation(conflictingInput, 12)))
                .isInstanceOf(GraphProjectionException.class)
                .extracting(exception -> ((GraphProjectionException) exception).failureType())
                .isEqualTo(GraphProjectionFailureType.INVALID_PROJECTION_INPUT);
        assertThat(writer.hasEntity(first.identity())).isTrue();
        assertThat(writer.hasEntity(second.identity())).isFalse();
    }

    @Test
    void workspaceScopedCleanupDoesNotTouchAnotherWorkspace() {
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphWorkspaceScope firstWorkspace = new GraphWorkspaceScope(34);
        GraphWorkspaceScope secondWorkspace = new GraphWorkspaceScope(35);
        GraphEntity first = entity(firstWorkspace, "same-key", "First", version);
        GraphEntity second = entity(secondWorkspace, "same-key", "Second", version);
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        writer.publish(new GraphProjectionInput(firstWorkspace, version, List.of(first), List.of()), 1);
        writer.publish(new GraphProjectionInput(secondWorkspace, version, List.of(second), List.of()), 1);

        GraphProjectionInput empty = new GraphProjectionInput(firstWorkspace, version,
                List.of(), List.of());
        writer.publish(empty, 2);
        GraphProjectionCleanupResult result = writer.removeStale(reconciliation(empty, 2));

        assertThat(result.status()).isEqualTo(GraphProjectionCleanupStatus.APPLIED);
        assertThat(result.removedEntities()).isEqualTo(1);
        assertThat(writer.hasEntity(first.identity())).isFalse();
        assertThat(writer.hasEntity(second.identity())).isTrue();
    }

    @Test
    void futureReconciliationCannotCleanAnOlderCurrentGeneration() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(36);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphProjectionInput currentInput = new GraphProjectionInput(workspace, version,
                List.of(first), List.of());
        GraphProjectionInput futureInput = new GraphProjectionInput(workspace, version,
                List.of(), List.of());
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        writer.publish(currentInput, 3);

        assertThatThrownBy(() -> writer.removeStale(reconciliation(futureInput, 4)))
                .isInstanceOf(GraphProjectionException.class)
                .extracting(exception -> ((GraphProjectionException) exception).failureType())
                .isEqualTo(GraphProjectionFailureType.PROJECTION_STALE);
        assertThat(writer.hasEntity(first.identity())).isTrue();
    }

    @Test
    void olderEntityWriteCannotResurrectStateAfterNewerGenerationIsPublished() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(38);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphEntity staleOnly = entity(workspace, "stale-only", "Stale only", version);
        GraphProjectionInput generationA = new GraphProjectionInput(workspace, version,
                List.of(first, staleOnly), List.of());
        GraphProjectionInput generationB = new GraphProjectionInput(workspace, version,
                List.of(first), List.of());
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        GraphProjectionWriteContext contextA = context(generationA, 10);
        writer.publish(generationA, 10);
        writer.publish(generationB, 11);

        GraphProjectionWriteResult result = writer.upsertEntity(contextA, staleOnly);

        assertThat(result.status()).isEqualTo(GraphProjectionWriteStatus.SUPERSEDED);
        assertThat(writer.hasEntity(staleOnly.identity())).isFalse();
        assertThat(writer.hasEntity(first.identity())).isTrue();
    }

    @Test
    void olderRelationWriteCannotResurrectStateAfterNewerGenerationIsPublished() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(39);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphEntity second = entity(workspace, "second", "Second", version);
        GraphRelation staleRelation = relation(first, second, GraphRelationType.LINKS_TO);
        GraphProjectionInput generationA = new GraphProjectionInput(workspace, version,
                List.of(first, second), List.of(staleRelation));
        GraphProjectionInput generationB = new GraphProjectionInput(workspace, version,
                List.of(first, second), List.of());
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        GraphProjectionWriteContext contextA = context(generationA, 20);
        writer.publish(generationA, 20);
        writer.publish(generationB, 21);

        GraphProjectionWriteResult result = writer.upsertRelation(contextA, staleRelation);

        assertThat(result.status()).isEqualTo(GraphProjectionWriteStatus.SUPERSEDED);
        assertThat(writer.hasRelation(staleRelation.identity())).isFalse();
    }

    @Test
    void matchingProofWriteAppliesAndRepeatedWriteIsIdempotent() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(40);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphEntity second = entity(workspace, "second", "Second", version);
        GraphRelation relation = relation(first, second, GraphRelationType.RELATED_TO);
        GraphProjectionInput input = new GraphProjectionInput(workspace, version,
                List.of(first, second), List.of(relation));
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        GraphProjectionWriteContext context = context(input, 30);
        writer.publish(new GraphProjectionInput(workspace, version, List.of(), List.of()), 29);

        GraphProjectionWriteResult entityApplied = writer.upsertEntity(context, first);
        GraphProjectionWriteResult secondApplied = writer.upsertEntity(context, second);
        GraphProjectionWriteResult entityRepeated = writer.upsertEntity(context, first);
        GraphProjectionWriteResult relationApplied = writer.upsertRelation(context, relation);
        GraphProjectionWriteResult relationRepeated = writer.upsertRelation(context, relation);

        assertThat(entityApplied.status()).isEqualTo(GraphProjectionWriteStatus.APPLIED);
        assertThat(secondApplied.status()).isEqualTo(GraphProjectionWriteStatus.APPLIED);
        assertThat(entityRepeated.status()).isEqualTo(GraphProjectionWriteStatus.NO_OP);
        assertThat(relationApplied.status()).isEqualTo(GraphProjectionWriteStatus.APPLIED);
        assertThat(relationRepeated.status()).isEqualTo(GraphProjectionWriteStatus.NO_OP);
        assertThat(writer.hasEntity(first.identity())).isFalse();
        assertThat(writer.hasRelation(relation.identity())).isFalse();

        assertThat(writer.publish(context).status()).isEqualTo(GraphProjectionWriteStatus.APPLIED);
        assertThat(writer.hasEntity(first.identity())).isTrue();
        assertThat(writer.hasEntity(second.identity())).isTrue();
        assertThat(writer.hasRelation(relation.identity())).isTrue();
        assertThat(writer.upsertEntity(context, first).status())
                .isEqualTo(GraphProjectionWriteStatus.NO_OP);
        assertThat(writer.upsertRelation(context, relation).status())
                .isEqualTo(GraphProjectionWriteStatus.NO_OP);
        assertThat(writer.publish(context).status()).isEqualTo(GraphProjectionWriteStatus.NO_OP);
    }

    @Test
    void sameGenerationConflictingProofFailsClosedForBothMutationKinds() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(41);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphEntity second = entity(workspace, "second", "Second", version);
        GraphRelation relation = relation(first, second, GraphRelationType.LINKS_TO);
        GraphProjectionInput current = new GraphProjectionInput(workspace, version,
                List.of(first, second), List.of(relation));
        GraphProjectionInput conflicting = new GraphProjectionInput(workspace, version,
                List.of(first), List.of());
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        writer.publish(current, 40);
        GraphProjectionWriteContext conflictingContext = context(conflicting, 40);

        assertThatThrownBy(() -> writer.upsertEntity(conflictingContext, first))
                .isInstanceOf(GraphProjectionException.class)
                .extracting(exception -> ((GraphProjectionException) exception).failureType())
                .isEqualTo(GraphProjectionFailureType.INVALID_PROJECTION_INPUT);
        assertThatThrownBy(() -> writer.upsertRelation(conflictingContext, relation))
                .isInstanceOf(GraphProjectionException.class)
                .extracting(exception -> ((GraphProjectionException) exception).failureType())
                .isEqualTo(GraphProjectionFailureType.INVALID_PROJECTION_INPUT);
        assertThat(writer.hasEntity(second.identity())).isTrue();
        assertThat(writer.hasRelation(relation.identity())).isTrue();
    }

    @Test
    void sameGenerationConflictingProofIsRejectedBeforeEitherOperationPublishes() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(42);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphEntity second = entity(workspace, "second", "Second", version);
        GraphProjectionInput firstInput = new GraphProjectionInput(workspace, version,
                List.of(first), List.of());
        GraphProjectionInput secondInput = new GraphProjectionInput(workspace, version,
                List.of(second), List.of());
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        GraphProjectionWriteContext firstContext = context(firstInput, 50);
        GraphProjectionWriteContext secondContext = context(secondInput, 50);

        assertThat(writer.upsertEntity(firstContext, first).status())
                .isEqualTo(GraphProjectionWriteStatus.APPLIED);
        assertThatThrownBy(() -> writer.upsertEntity(secondContext, second))
                .isInstanceOf(GraphProjectionException.class)
                .extracting(exception -> ((GraphProjectionException) exception).failureType())
                .isEqualTo(GraphProjectionFailureType.INVALID_PROJECTION_INPUT);
        assertThatThrownBy(() -> writer.publish(secondContext))
                .isInstanceOf(GraphProjectionException.class)
                .extracting(exception -> ((GraphProjectionException) exception).failureType())
                .isEqualTo(GraphProjectionFailureType.INVALID_PROJECTION_INPUT);
        assertThat(writer.publish(firstContext).status())
                .isEqualTo(GraphProjectionWriteStatus.APPLIED);
        assertThat(writer.hasEntity(first.identity())).isTrue();
        assertThat(writer.hasEntity(second.identity())).isFalse();
    }

    @Test
    void crossWorkspaceAndIncompatibleVersionWritesFailClosed() {
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphProjectionVersion incompatibleVersion = new GraphProjectionVersion("graph-projection-v2");
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(43);
        GraphWorkspaceScope otherWorkspace = new GraphWorkspaceScope(44);
        GraphEntity local = entity(workspace, "local", "Local", version);
        GraphEntity foreign = entity(otherWorkspace, "foreign", "Foreign", version);
        GraphEntity incompatible = entity(workspace, "incompatible", "Incompatible", incompatibleVersion);
        GraphProjectionInput input = new GraphProjectionInput(workspace, version,
                List.of(local), List.of());
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        GraphProjectionWriteContext context = context(input, 60);

        assertThatThrownBy(() -> writer.upsertEntity(context, foreign))
                .isInstanceOf(GraphProjectionException.class)
                .extracting(exception -> ((GraphProjectionException) exception).failureType())
                .isEqualTo(GraphProjectionFailureType.CROSS_WORKSPACE);
        assertThatThrownBy(() -> writer.upsertEntity(context, incompatible))
                .isInstanceOf(GraphProjectionException.class)
                .extracting(exception -> ((GraphProjectionException) exception).failureType())
                .isEqualTo(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
        assertThatThrownBy(() -> writer.upsertRelation(context,
                relation(foreign, foreign, GraphRelationType.RELATED_TO)))
                .isInstanceOf(GraphProjectionException.class)
                .extracting(exception -> ((GraphProjectionException) exception).failureType())
                .isEqualTo(GraphProjectionFailureType.CROSS_WORKSPACE);
    }

    @Test
    void newerWriteAndOlderCleanupInterleaveWithoutHidingNewerStagedState() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(45);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphEntity second = entity(workspace, "second", "Second", version);
        GraphProjectionInput generationA = new GraphProjectionInput(workspace, version,
                List.of(first), List.of());
        GraphProjectionInput generationB = new GraphProjectionInput(workspace, version,
                List.of(first, second), List.of());
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        writer.publish(generationA, 70);
        GraphProjectionWriteContext contextB = context(generationB, 71);

        assertThat(writer.upsertEntity(contextB, second).status())
                .isEqualTo(GraphProjectionWriteStatus.APPLIED);
        assertThat(writer.removeStale(reconciliation(generationA, 70)).status())
                .isEqualTo(GraphProjectionCleanupStatus.NO_OP);
        assertThat(writer.hasEntity(second.identity())).isFalse();
        assertThat(writer.publish(contextB).status()).isEqualTo(GraphProjectionWriteStatus.APPLIED);
        assertThat(writer.hasEntity(second.identity())).isTrue();
    }

    @Test
    void newerPublishWinsAndOlderWriteAndCleanupAreBothNoMutation() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(46);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphEntity staleOnly = entity(workspace, "stale-only", "Stale only", version);
        GraphProjectionInput generationA = new GraphProjectionInput(workspace, version,
                List.of(first, staleOnly), List.of());
        GraphProjectionInput generationB = new GraphProjectionInput(workspace, version,
                List.of(first), List.of());
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        GraphProjectionReconciliation reconciliationA = reconciliation(generationA, 80);
        GraphProjectionWriteContext contextA = reconciliationA.writeContext();
        writer.publish(generationA, 80);
        writer.publish(generationB, 81);

        assertThat(writer.upsertEntity(contextA, staleOnly).status())
                .isEqualTo(GraphProjectionWriteStatus.SUPERSEDED);
        assertThat(writer.removeStale(reconciliationA).status())
                .isEqualTo(GraphProjectionCleanupStatus.SUPERSEDED);
        assertThat(writer.hasEntity(staleOnly.identity())).isFalse();
        assertThat(writer.hasEntity(first.identity())).isTrue();
    }

    @Test
    void cleanupDoesNotDeleteRowsStagedForAStillUnpublishedNewerGeneration() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(47);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphEntity staleOnly = entity(workspace, "stale-only", "Stale only", version);
        GraphEntity newerOnly = entity(workspace, "newer-only", "Newer only", version);
        GraphProjectionInput oldGeneration = new GraphProjectionInput(workspace, version,
                List.of(first, staleOnly), List.of());
        GraphProjectionInput generationA = new GraphProjectionInput(workspace, version,
                List.of(first), List.of());
        GraphProjectionInput generationB = new GraphProjectionInput(workspace, version,
                List.of(first, newerOnly), List.of());
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        writer.publish(oldGeneration, 89);
        writer.publish(generationA, 90);
        GraphProjectionWriteContext contextB = context(generationB, 91);
        assertThat(writer.upsertEntity(contextB, newerOnly).status())
                .isEqualTo(GraphProjectionWriteStatus.APPLIED);

        GraphProjectionCleanupResult cleanup = writer.removeStale(reconciliation(generationA, 90));

        assertThat(cleanup.status()).isEqualTo(GraphProjectionCleanupStatus.APPLIED);
        assertThat(cleanup.removedEntities()).isEqualTo(1);
        assertThat(writer.hasEntity(staleOnly.identity())).isFalse();
        assertThat(writer.hasEntity(newerOnly.identity())).isFalse();
        assertThat(writer.publish(contextB).status()).isEqualTo(GraphProjectionWriteStatus.APPLIED);
        assertThat(writer.hasEntity(newerOnly.identity())).isTrue();
    }

    @Test
    void olderClearCannotRemoveNewerCurrentGeneration() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(49);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphEntity newer = entity(workspace, "newer", "Newer", version);
        GraphRelation newerRelation = relation(first, newer, GraphRelationType.RELATED_TO);
        GraphProjectionInput generationA = new GraphProjectionInput(workspace, version,
                List.of(first), List.of());
        GraphProjectionInput generationB = new GraphProjectionInput(workspace, version,
                List.of(first, newer), List.of(newerRelation));
        GraphProjectionWriteContext contextA = context(generationA, 100);
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        writer.publish(generationA, 100);
        writer.publish(generationB, 101);

        GraphProjectionWriteResult result = writer.clearWorkspace(workspace, contextA);

        assertThat(result.status()).isEqualTo(GraphProjectionWriteStatus.SUPERSEDED);
        assertThat(writer.hasEntity(first.identity())).isTrue();
        assertThat(writer.hasEntity(newer.identity())).isTrue();
        assertThat(writer.hasRelation(newerRelation.identity())).isTrue();
    }

    @Test
    void olderClearPreservesNewerStagedRowsForLaterPublish() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(50);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity current = entity(workspace, "current", "Current", version);
        GraphEntity newer = entity(workspace, "newer", "Newer", version);
        GraphProjectionInput generationA = new GraphProjectionInput(workspace, version,
                List.of(current), List.of());
        GraphProjectionInput generationB = new GraphProjectionInput(workspace, version,
                List.of(newer), List.of());
        GraphProjectionWriteContext contextA = context(generationA, 102);
        GraphProjectionWriteContext contextB = context(generationB, 103);
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        writer.publish(generationA, 102);
        assertThat(writer.upsertEntity(contextB, newer).status())
                .isEqualTo(GraphProjectionWriteStatus.APPLIED);

        GraphProjectionWriteResult clear = writer.clearWorkspace(workspace, contextA);

        assertThat(clear.status()).isEqualTo(GraphProjectionWriteStatus.APPLIED);
        assertThat(writer.hasEntity(current.identity())).isFalse();
        assertThat(writer.hasStagedEntity(newer.identity(), contextB)).isTrue();
        assertThat(writer.publish(contextB).status()).isEqualTo(GraphProjectionWriteStatus.APPLIED);
        assertThat(writer.hasEntity(newer.identity())).isTrue();
    }

    @Test
    void matchingClearRemovesCurrentWorkspaceProjection() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(51);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphEntity second = entity(workspace, "second", "Second", version);
        GraphRelation relation = relation(first, second, GraphRelationType.RELATED_TO);
        GraphProjectionInput input = new GraphProjectionInput(workspace, version,
                List.of(first, second), List.of(relation));
        GraphProjectionWriteContext context = context(input, 104);
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        writer.publish(input, 104);

        GraphProjectionWriteResult result = writer.clearWorkspace(workspace, context);

        assertThat(result.status()).isEqualTo(GraphProjectionWriteStatus.APPLIED);
        assertThat(result.mutated()).isTrue();
        assertThat(writer.hasEntity(first.identity())).isFalse();
        assertThat(writer.hasEntity(second.identity())).isFalse();
        assertThat(writer.hasRelation(relation.identity())).isFalse();
        assertThat(writer.hasCurrentSnapshot(workspace)).isFalse();
    }

    @Test
    void sameGenerationConflictingClearProofFailsClosed() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(52);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphEntity second = entity(workspace, "second", "Second", version);
        GraphProjectionInput current = new GraphProjectionInput(workspace, version,
                List.of(first), List.of());
        GraphProjectionInput conflicting = new GraphProjectionInput(workspace, version,
                List.of(second), List.of());
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        writer.publish(current, 105);

        assertThatThrownBy(() -> writer.clearWorkspace(workspace, context(conflicting, 105)))
                .isInstanceOf(GraphProjectionException.class)
                .extracting(exception -> ((GraphProjectionException) exception).failureType())
                .isEqualTo(GraphProjectionFailureType.INVALID_PROJECTION_INPUT);
        assertThat(writer.hasEntity(first.identity())).isTrue();
        assertThat(writer.hasEntity(second.identity())).isFalse();
    }

    @Test
    void crossWorkspaceClearProofFailsClosed() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(53);
        GraphWorkspaceScope foreignWorkspace = new GraphWorkspaceScope(54);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphProjectionInput input = new GraphProjectionInput(workspace, version,
                List.of(first), List.of());
        GraphProjectionWriteContext context = context(input, 106);
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        writer.publish(input, 106);

        assertThatThrownBy(() -> writer.clearWorkspace(foreignWorkspace, context))
                .isInstanceOf(GraphProjectionException.class)
                .extracting(exception -> ((GraphProjectionException) exception).failureType())
                .isEqualTo(GraphProjectionFailureType.CROSS_WORKSPACE);
        assertThat(writer.hasEntity(first.identity())).isTrue();
    }

    @Test
    void incompatibleProjectionVersionClearFailsClosed() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(55);
        GraphProjectionVersion currentVersion = GraphProjectionVersion.initial();
        GraphProjectionVersion incompatibleVersion = new GraphProjectionVersion("graph-projection-v2");
        GraphEntity first = entity(workspace, "first", "First", currentVersion);
        GraphProjectionInput current = new GraphProjectionInput(workspace, currentVersion,
                List.of(first), List.of());
        GraphProjectionInput incompatible = new GraphProjectionInput(workspace, incompatibleVersion,
                List.of(), List.of());
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        writer.publish(current, 107);

        assertThatThrownBy(() -> writer.clearWorkspace(workspace, context(incompatible, 108)))
                .isInstanceOf(GraphProjectionException.class)
                .extracting(exception -> ((GraphProjectionException) exception).failureType())
                .isEqualTo(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
        assertThat(writer.hasEntity(first.identity())).isTrue();
    }

    @Test
    void clearDoesNotTouchAnotherWorkspace() {
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphWorkspaceScope firstWorkspace = new GraphWorkspaceScope(56);
        GraphWorkspaceScope secondWorkspace = new GraphWorkspaceScope(57);
        GraphEntity first = entity(firstWorkspace, "same-key", "First", version);
        GraphEntity second = entity(secondWorkspace, "same-key", "Second", version);
        GraphProjectionInput firstInput = new GraphProjectionInput(firstWorkspace, version,
                List.of(first), List.of());
        GraphProjectionInput secondInput = new GraphProjectionInput(secondWorkspace, version,
                List.of(second), List.of());
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        writer.publish(firstInput, 109);
        writer.publish(secondInput, 109);

        GraphProjectionWriteResult result = writer.clearWorkspace(firstWorkspace,
                context(firstInput, 109));

        assertThat(result.status()).isEqualTo(GraphProjectionWriteStatus.APPLIED);
        assertThat(writer.hasEntity(first.identity())).isFalse();
        assertThat(writer.hasEntity(second.identity())).isTrue();
        assertThat(writer.hasCurrentSnapshot(secondWorkspace)).isTrue();
    }

    @Test
    void clearAndNewerPublishHaveDeterministicNoStaleMutationOrdering() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(58);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphEntity newer = entity(workspace, "newer", "Newer", version);
        GraphProjectionInput generationA = new GraphProjectionInput(workspace, version,
                List.of(first), List.of());
        GraphProjectionInput generationB = new GraphProjectionInput(workspace, version,
                List.of(newer), List.of());
        GraphProjectionWriteContext contextA = context(generationA, 110);
        GraphProjectionWriteContext contextB = context(generationB, 111);
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        writer.publish(generationA, 110);
        assertThat(writer.upsertEntity(contextB, newer).status())
                .isEqualTo(GraphProjectionWriteStatus.APPLIED);

        assertThat(writer.clearWorkspace(workspace, contextA).status())
                .isEqualTo(GraphProjectionWriteStatus.APPLIED);
        assertThat(writer.publish(contextB).status()).isEqualTo(GraphProjectionWriteStatus.APPLIED);
        assertThat(writer.clearWorkspace(workspace, contextA).status())
                .isEqualTo(GraphProjectionWriteStatus.SUPERSEDED);
        assertThat(writer.hasEntity(newer.identity())).isTrue();
    }

    @Test
    void repeatedClearWithTheSameProofIsIdempotent() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(59);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphProjectionInput input = new GraphProjectionInput(workspace, version,
                List.of(first), List.of());
        GraphProjectionWriteContext context = context(input, 112);
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        writer.publish(input, 112);

        GraphProjectionWriteResult firstClear = writer.clearWorkspace(workspace, context);
        GraphProjectionWriteResult repeatedClear = writer.clearWorkspace(workspace, context);

        assertThat(firstClear.status()).isEqualTo(GraphProjectionWriteStatus.APPLIED);
        assertThat(repeatedClear.status()).isEqualTo(GraphProjectionWriteStatus.NO_OP);
        assertThat(repeatedClear.mutated()).isFalse();
        assertThat(writer.hasEntity(first.identity())).isFalse();
    }

    @Test
    void futureClearCannotDeleteAnOlderCurrentGeneration() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(60);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphProjectionInput current = new GraphProjectionInput(workspace, version,
                List.of(first), List.of());
        GraphProjectionInput future = new GraphProjectionInput(workspace, version,
                List.of(), List.of());
        InMemoryGraphProjectionWriter writer = new InMemoryGraphProjectionWriter();
        writer.publish(current, 113);

        assertThatThrownBy(() -> writer.clearWorkspace(workspace, context(future, 114)))
                .isInstanceOf(GraphProjectionException.class)
                .extracting(exception -> ((GraphProjectionException) exception).failureType())
                .isEqualTo(GraphProjectionFailureType.PROJECTION_STALE);
        assertThat(writer.hasEntity(first.identity())).isTrue();
    }

    @Test
    void writerDoesNotExposeAnUnownedWorkspaceClear() {
        assertThat(Arrays.stream(GraphProjectionWriter.class.getMethods())
                .filter(method -> method.getName().equals("clearWorkspace"))
                .filter(method -> Arrays.equals(method.getParameterTypes(),
                        new Class<?>[]{GraphWorkspaceScope.class}))
                .toList()).isEmpty();
    }

    @Test
    void cleanupResultRejectsStatusAndCountContradictions() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(48);

        assertThatThrownBy(() -> new GraphProjectionCleanupResult(
                GraphProjectionCleanupStatus.NO_OP, workspace, 1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphProjectionCleanupResult(
                GraphProjectionCleanupStatus.SUPERSEDED, workspace, 1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphProjectionCleanupResult(
                GraphProjectionCleanupStatus.APPLIED, workspace, 1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static GraphProjectionReconciliation reconciliation(GraphProjectionInput input,
                                                                long generation) {
        return GraphProjectionReconciliation.from(input, GraphProjectionSnapshot.of(input, generation));
    }

    private static GraphProjectionWriteContext context(GraphProjectionInput input, long generation) {
        return GraphProjectionWriteContext.of(input, generation);
    }

    private static GraphEntity entity(GraphWorkspaceScope workspace, String key, String name,
                                      GraphProjectionVersion version) {
        GraphAuthorityReference authority = new GraphAuthorityReference(workspace,
                GraphAuthorityKind.CANONICAL_METADATA, "metadata-" + key);
        return new GraphEntity(GraphEntityIdentity.of(workspace, GraphEntityType.CONCEPT, key), name,
                GraphProvenance.of(authority, GraphFreshness.contentHash(HASH)), GraphMetadata.empty(), version);
    }

    private static GraphRelation relation(GraphEntity source, GraphEntity target,
                                          GraphRelationType type) {
        return new GraphRelation(GraphRelationIdentity.of(source.identity(), type, target.identity()),
                source.identity(), type, target.identity(), source.provenance(), GraphMetadata.empty(),
                source.projectionVersion());
    }

    /** Minimal deterministic adapter used only to execute the provider-neutral generation contract. */
    private static final class InMemoryGraphProjectionWriter implements GraphProjectionWriter {
        private final Map<GraphEntityIdentity, Map<GraphProjectionSnapshot, GraphEntity>> entities = new HashMap<>();
        private final Map<GraphRelationIdentity, Map<GraphProjectionSnapshot, GraphRelation>> relations = new HashMap<>();
        private final Map<GraphWorkspaceScope, GraphProjectionSnapshot> currentSnapshots = new HashMap<>();
        private final Map<GraphWorkspaceScope, Map<Long, GraphProjectionSnapshot>> operationOwners = new HashMap<>();
        private final Map<GraphWorkspaceScope, Set<GraphProjectionSnapshot>> clearedSnapshots = new HashMap<>();

        void publish(GraphProjectionInput input, long generation) {
            GraphProjectionWriteContext context = context(input, generation);
            input.entities().forEach(entity -> upsertEntity(context, entity));
            input.relations().forEach(relation -> upsertRelation(context, relation));
            assertThat(publish(context).status())
                    .isNotEqualTo(GraphProjectionWriteStatus.SUPERSEDED);
        }

        @Override
        public synchronized GraphProjectionWriteResult upsertEntity(GraphProjectionWriteContext context,
                                                                     GraphEntity entity) {
            validateEntity(context, entity);
            GraphProjectionWriteStatus currentStatus = validateCurrent(context);
            if (currentStatus == GraphProjectionWriteStatus.SUPERSEDED) {
                return GraphProjectionWriteResult.superseded(context);
            }
            Map<GraphProjectionSnapshot, GraphEntity> rows = entities.computeIfAbsent(
                    entity.identity(), ignored -> new HashMap<>());
            GraphEntity previous = rows.putIfAbsent(context.snapshot(), entity);
            if (previous == null) {
                return GraphProjectionWriteResult.applied(context);
            }
            if (!previous.equals(entity)) {
                throw invalidInput("Graph entity contents conflict with its write proof");
            }
            return GraphProjectionWriteResult.noOp(context);
        }

        @Override
        public synchronized GraphProjectionWriteResult upsertRelation(GraphProjectionWriteContext context,
                                                                       GraphRelation relation) {
            validateRelation(context, relation);
            GraphProjectionWriteStatus currentStatus = validateCurrent(context);
            if (currentStatus == GraphProjectionWriteStatus.SUPERSEDED) {
                return GraphProjectionWriteResult.superseded(context);
            }
            Map<GraphProjectionSnapshot, GraphRelation> rows = relations.computeIfAbsent(
                    relation.identity(), ignored -> new HashMap<>());
            GraphRelation previous = rows.putIfAbsent(context.snapshot(), relation);
            if (previous == null) {
                return GraphProjectionWriteResult.applied(context);
            }
            if (!previous.equals(relation)) {
                throw invalidInput("Graph relation contents conflict with its write proof");
            }
            return GraphProjectionWriteResult.noOp(context);
        }

        @Override
        public synchronized GraphProjectionWriteResult publish(GraphProjectionWriteContext context) {
            requireContext(context);
            GraphProjectionSnapshot current = currentSnapshots.get(context.workspace());
            GraphProjectionWriteStatus currentStatus = validateCurrent(context);
            if (currentStatus == GraphProjectionWriteStatus.SUPERSEDED) {
                return GraphProjectionWriteResult.superseded(context);
            }
            if (current != null && context.owns(current)) {
                return GraphProjectionWriteResult.noOp(context);
            }
            currentSnapshots.put(context.workspace(), context.snapshot());
            return GraphProjectionWriteResult.applied(context);
        }

        @Override
        public synchronized GraphProjectionCleanupResult removeStale(GraphProjectionReconciliation reconciliation) {
            if (reconciliation == null) {
                throw new IllegalArgumentException("Graph reconciliation is required");
            }
            GraphProjectionSnapshot current = requireCurrent(reconciliation.workspace());
            if (!reconciliation.snapshot().projectionVersion().equals(current.projectionVersion())) {
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
            }
            if (reconciliation.isSupersededBy(current)) {
                return GraphProjectionCleanupResult.superseded(reconciliation);
            }
            if (reconciliation.conflictsWith(current)) {
                throw invalidInput("Graph cleanup proof conflicts with current generation");
            }
            if (!reconciliation.owns(current)) {
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE);
            }
            claim(reconciliation.writeContext());

            int removedEntities = removeStaleEntities(reconciliation, current);
            int removedRelations = removeStaleRelations(reconciliation, current);
            return GraphProjectionCleanupResult.applied(reconciliation, removedEntities, removedRelations);
        }

        @Override
        public synchronized GraphProjectionWriteResult clearWorkspace(GraphWorkspaceScope workspace,
                                                                       GraphProjectionWriteContext context) {
            requireWorkspace(workspace);
            requireContext(context);
            if (!workspace.equals(context.workspace())) {
                throw new GraphProjectionException(GraphProjectionFailureType.CROSS_WORKSPACE);
            }
            GraphProjectionSnapshot current = currentSnapshots.get(workspace);
            if (current != null && !context.projectionVersion().equals(current.projectionVersion())) {
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
            }
            if (context.isSupersededBy(current)) {
                return GraphProjectionWriteResult.superseded(context);
            }
            if (context.conflictsWith(current)) {
                throw invalidInput("Graph clear proof conflicts with current generation");
            }
            if (current == null) {
                Set<GraphProjectionSnapshot> cleared = clearedSnapshots.getOrDefault(workspace, Set.of());
                if (cleared.stream().anyMatch(snapshot -> snapshot.generation() > context.generation())) {
                    return GraphProjectionWriteResult.superseded(context);
                }
                if (cleared.contains(context.snapshot())) {
                    return GraphProjectionWriteResult.noOp(context);
                }
                if (cleared.stream().anyMatch(snapshot -> snapshot.generation() == context.generation())) {
                    throw invalidInput("Graph clear proof conflicts with an applied generation");
                }
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_NOT_READY);
            }
            if (!context.owns(current)) {
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE);
            }

            claim(context);
            entities.entrySet().stream()
                    .filter(entry -> workspace.equals(entry.getKey().workspace()))
                    .forEach(entry -> removeRowsThroughGeneration(entry.getValue(), context.generation()));
            relations.entrySet().stream()
                    .filter(entry -> workspace.equals(entry.getKey().workspace()))
                    .forEach(entry -> removeRowsThroughGeneration(entry.getValue(), context.generation()));
            entities.entrySet().removeIf(entry -> workspace.equals(entry.getKey().workspace())
                    && entry.getValue().isEmpty());
            relations.entrySet().removeIf(entry -> workspace.equals(entry.getKey().workspace())
                    && entry.getValue().isEmpty());
            currentSnapshots.remove(workspace, current);
            Map<Long, GraphProjectionSnapshot> owners = operationOwners.get(workspace);
            if (owners != null) {
                owners.keySet().removeIf(generation -> generation <= context.generation());
                if (owners.isEmpty()) {
                    operationOwners.remove(workspace);
                }
            }
            clearedSnapshots.computeIfAbsent(workspace, ignored -> new HashSet<>()).add(context.snapshot());
            return GraphProjectionWriteResult.applied(context);
        }

        boolean hasEntity(GraphEntityIdentity identity) {
            GraphProjectionSnapshot current = currentSnapshots.get(identity.workspace());
            return current != null && entities.getOrDefault(identity, Map.of()).containsKey(current);
        }

        boolean hasRelation(GraphRelationIdentity identity) {
            GraphProjectionSnapshot current = currentSnapshots.get(identity.workspace());
            return current != null && relations.getOrDefault(identity, Map.of()).containsKey(current);
        }

        boolean hasStagedEntity(GraphEntityIdentity identity, GraphProjectionWriteContext context) {
            return entities.getOrDefault(identity, Map.of()).containsKey(context.snapshot());
        }

        boolean hasCurrentSnapshot(GraphWorkspaceScope workspace) {
            return currentSnapshots.containsKey(workspace);
        }

        private GraphProjectionWriteStatus validateCurrent(GraphProjectionWriteContext context) {
            requireContext(context);
            GraphProjectionSnapshot current = currentSnapshots.get(context.workspace());
            if (current != null && !context.projectionVersion().equals(current.projectionVersion())) {
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
            }
            if (context.isSupersededBy(current)) {
                return GraphProjectionWriteStatus.SUPERSEDED;
            }
            if (context.conflictsWith(current)) {
                throw invalidInput("Graph write proof conflicts with current generation");
            }
            claim(context);
            return null;
        }

        private void validateEntity(GraphProjectionWriteContext context, GraphEntity entity) {
            requireContext(context);
            if (entity == null || !context.workspace().equals(entity.identity().workspace())) {
                throw new GraphProjectionException(GraphProjectionFailureType.CROSS_WORKSPACE);
            }
            if (!context.projectionVersion().equals(entity.projectionVersion())) {
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
            }
            if (!context.matches(entity)) {
                throw invalidInput("Graph entity does not belong to its write proof");
            }
        }

        private void validateRelation(GraphProjectionWriteContext context, GraphRelation relation) {
            requireContext(context);
            if (relation == null || !context.workspace().equals(relation.identity().workspace())) {
                throw new GraphProjectionException(GraphProjectionFailureType.CROSS_WORKSPACE);
            }
            if (!context.projectionVersion().equals(relation.projectionVersion())) {
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
            }
            if (!context.matches(relation)) {
                throw invalidInput("Graph relation does not belong to its write proof");
            }
        }

        private int removeStaleEntities(GraphProjectionReconciliation reconciliation,
                                        GraphProjectionSnapshot current) {
            Set<GraphEntityIdentity> active = new HashSet<>(reconciliation.activeEntities());
            int removed = 0;
            for (var entry : entities.entrySet()) {
                if (!entry.getKey().workspace().equals(reconciliation.workspace())
                        || active.contains(entry.getKey())) {
                    continue;
                }
                removed += removeRowsThroughGeneration(entry.getValue(), current.generation());
            }
            return removed;
        }

        private int removeStaleRelations(GraphProjectionReconciliation reconciliation,
                                         GraphProjectionSnapshot current) {
            Set<GraphRelationIdentity> active = new HashSet<>(reconciliation.activeRelations());
            int removed = 0;
            for (var entry : relations.entrySet()) {
                if (!entry.getKey().workspace().equals(reconciliation.workspace())
                        || active.contains(entry.getKey())) {
                    continue;
                }
                removed += removeRowsThroughGeneration(entry.getValue(), current.generation());
            }
            return removed;
        }

        private static <T> int removeRowsThroughGeneration(Map<GraphProjectionSnapshot, T> rows,
                                                            long generation) {
            int before = rows.size();
            rows.keySet().removeIf(snapshot -> snapshot.generation() <= generation);
            return before - rows.size();
        }

        private GraphProjectionSnapshot requireCurrent(GraphWorkspaceScope workspace) {
            requireWorkspace(workspace);
            GraphProjectionSnapshot current = currentSnapshots.get(workspace);
            if (current == null) {
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_NOT_READY);
            }
            return current;
        }

        private void claim(GraphProjectionWriteContext context) {
            Map<Long, GraphProjectionSnapshot> generations = operationOwners.computeIfAbsent(
                    context.workspace(), ignored -> new HashMap<>());
            GraphProjectionSnapshot previous = generations.putIfAbsent(context.generation(),
                    context.snapshot());
            if (previous != null && !previous.equals(context.snapshot())) {
                throw invalidInput("Graph generation already has another write proof");
            }
        }

        private static void requireContext(GraphProjectionWriteContext context) {
            if (context == null) {
                throw new IllegalArgumentException("Graph projection write context is required");
            }
        }

        private static void requireWorkspace(GraphWorkspaceScope workspace) {
            if (workspace == null) {
                throw new IllegalArgumentException("Graph workspace is required");
            }
        }

        private static GraphProjectionException invalidInput(String diagnostic) {
            return new GraphProjectionException(new GraphProjectionFailure(
                    GraphProjectionFailureType.INVALID_PROJECTION_INPUT, diagnostic));
        }
    }
}
