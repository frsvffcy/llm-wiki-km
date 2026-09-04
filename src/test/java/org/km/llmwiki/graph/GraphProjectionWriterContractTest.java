package org.km.llmwiki.graph;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    void cleanupResultRejectsStatusAndCountContradictions() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(37);

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
        private final Map<GraphEntityIdentity, StoredEntity> entities = new HashMap<>();
        private final Map<GraphRelationIdentity, StoredRelation> relations = new HashMap<>();
        private final Map<GraphWorkspaceScope, GraphProjectionSnapshot> currentSnapshots = new HashMap<>();

        void publish(GraphProjectionInput input, long generation) {
            GraphProjectionSnapshot snapshot = GraphProjectionSnapshot.of(input, generation);
            GraphProjectionSnapshot current = currentSnapshots.get(input.workspace());
            if (current != null && current.generation() > generation) {
                return;
            }
            currentSnapshots.put(input.workspace(), snapshot);
            input.entities().forEach(entity -> entities.put(entity.identity(),
                    new StoredEntity(input.workspace(), generation)));
            input.relations().forEach(relation -> relations.put(relation.identity(),
                    new StoredRelation(input.workspace(), generation)));
        }

        @Override
        public void upsertEntity(GraphWorkspaceScope workspace, GraphEntity entity) {
            requireWorkspace(workspace, entity.identity().workspace());
            GraphProjectionSnapshot current = requireCurrent(workspace);
            entities.put(entity.identity(), new StoredEntity(workspace, current.generation()));
        }

        @Override
        public void upsertRelation(GraphWorkspaceScope workspace, GraphRelation relation) {
            requireWorkspace(workspace, relation.identity().workspace());
            GraphProjectionSnapshot current = requireCurrent(workspace);
            relations.put(relation.identity(), new StoredRelation(workspace, current.generation()));
        }

        @Override
        public GraphProjectionCleanupResult removeStale(GraphProjectionReconciliation reconciliation) {
            GraphProjectionSnapshot current = requireCurrent(reconciliation.workspace());
            if (reconciliation.isSupersededBy(current)) {
                return GraphProjectionCleanupResult.superseded(reconciliation);
            }
            if (reconciliation.conflictsWith(current)) {
                throw new GraphProjectionException(GraphProjectionFailureType.INVALID_PROJECTION_INPUT);
            }
            if (!reconciliation.owns(current)) {
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE);
            }

            int removedEntities = removeStaleEntities(reconciliation);
            int removedRelations = removeStaleRelations(reconciliation);
            return GraphProjectionCleanupResult.applied(reconciliation, removedEntities, removedRelations);
        }

        @Override
        public void clearWorkspace(GraphWorkspaceScope workspace) {
            entities.entrySet().removeIf(entry -> entry.getValue().workspace().equals(workspace));
            relations.entrySet().removeIf(entry -> entry.getValue().workspace().equals(workspace));
            currentSnapshots.remove(workspace);
        }

        boolean hasEntity(GraphEntityIdentity identity) {
            return entities.containsKey(identity);
        }

        boolean hasRelation(GraphRelationIdentity identity) {
            return relations.containsKey(identity);
        }

        private int removeStaleEntities(GraphProjectionReconciliation reconciliation) {
            int before = entities.size();
            List<GraphEntityIdentity> active = reconciliation.activeEntities();
            entities.entrySet().removeIf(entry -> entry.getValue().workspace().equals(reconciliation.workspace())
                    && !active.contains(entry.getKey()));
            return before - entities.size();
        }

        private int removeStaleRelations(GraphProjectionReconciliation reconciliation) {
            int before = relations.size();
            List<GraphRelationIdentity> active = reconciliation.activeRelations();
            relations.entrySet().removeIf(entry -> entry.getValue().workspace().equals(reconciliation.workspace())
                    && !active.contains(entry.getKey()));
            return before - relations.size();
        }

        private GraphProjectionSnapshot requireCurrent(GraphWorkspaceScope workspace) {
            GraphProjectionSnapshot current = currentSnapshots.get(workspace);
            if (current == null) {
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_NOT_READY);
            }
            return current;
        }

        private static void requireWorkspace(GraphWorkspaceScope expected, GraphWorkspaceScope actual) {
            if (expected == null || !expected.equals(actual)) {
                throw new GraphProjectionException(GraphProjectionFailureType.CROSS_WORKSPACE);
            }
        }

        private record StoredEntity(GraphWorkspaceScope workspace, long generation) {
        }

        private record StoredRelation(GraphWorkspaceScope workspace, long generation) {
        }
    }
}
