package org.km.llmwiki.graph;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("contract")
class GraphProjectionContractTest {

    private static final String HASH = "c".repeat(64);

    @Test
    void projectionInputSortsRebuildInputsAndProducesStableFingerprint() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(21);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphEntity second = entity(workspace, "second", "Second", version);
        GraphRelation relation = GraphRelation.of(first.identity(), GraphRelationType.RELATED_TO,
                second.identity(), first.provenance(), GraphMetadata.of(Map.of("confidence", "high")));

        GraphProjectionInput original = new GraphProjectionInput(workspace, version,
                List.of(second, first), List.of(relation));
        GraphProjectionInput rebuilt = new GraphProjectionInput(workspace, version,
                List.of(first, second), List.of(relation));

        assertThat(original.entities()).extracting(entity -> entity.identity().canonicalKey())
                .containsExactly("first", "second");
        assertThat(original.sourceFingerprint()).isEqualTo(rebuilt.sourceFingerprint())
                .hasSize(64).matches("[0-9a-f]{64}");
        assertThat(original.entities()).isUnmodifiable();
        assertThat(original.relations()).isUnmodifiable();
    }

    @Test
    void projectionInputRejectsCrossWorkspaceAndDuplicateState() {
        GraphWorkspaceScope firstWorkspace = new GraphWorkspaceScope(21);
        GraphWorkspaceScope secondWorkspace = new GraphWorkspaceScope(22);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(firstWorkspace, "first", "First", version);
        GraphEntity second = entity(secondWorkspace, "second", "Second", version);

        assertThatThrownBy(() -> new GraphProjectionInput(firstWorkspace, version,
                List.of(first, second), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace");
        assertThatThrownBy(() -> new GraphProjectionInput(firstWorkspace, version,
                List.of(first, first), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void reconciliationIsWorkspaceScopedAndDeterministic() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(21);
        GraphProjectionVersion version = GraphProjectionVersion.initial();
        GraphEntity first = entity(workspace, "first", "First", version);
        GraphEntity second = entity(workspace, "second", "Second", version);
        GraphRelation relation = GraphRelation.of(first.identity(), GraphRelationType.LINKS_TO,
                second.identity(), first.provenance(), GraphMetadata.empty());
        String token = "d".repeat(64);
        GraphProjectionSnapshot snapshot = new GraphProjectionSnapshot(workspace, version, 3,
                "e".repeat(64), token);
        GraphProjectionReconciliation reconciliation = new GraphProjectionReconciliation(workspace,
                snapshot, List.of(second.identity(), first.identity()), List.of(relation.identity()));

        assertThat(reconciliation.activeEntities()).containsExactly(first.identity(), second.identity());
        GraphProjectionSnapshot rebuilt = GraphProjectionSnapshot.of(
                new GraphProjectionInput(workspace, version, List.of(first, second), List.of(relation)), 3);
        assertThat(rebuilt.snapshotToken()).isEqualTo(GraphProjectionSnapshot.of(
                new GraphProjectionInput(workspace, version, List.of(second, first), List.of(relation)), 3)
                .snapshotToken());
        assertThatThrownBy(() -> new GraphProjectionReconciliation(new GraphWorkspaceScope(22),
                snapshot, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace");
        assertThatThrownBy(() -> new GraphProjectionReconciliation(workspace, snapshot,
                List.of(first.identity()), List.of(relation.identity())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orphan");
    }

    @Test
    void exposesTypedFailureWithoutMappingEveryRuntimeExceptionToUnavailable() {
        GraphProjectionException failure = new GraphProjectionException(
                new GraphProjectionFailure(GraphProjectionFailureType.CAPABILITY_UNAVAILABLE,
                        "authorization: Bearer sk-secret-123 " + "safe diagnostic"));

        assertThat(failure.failureType()).isEqualTo(GraphProjectionFailureType.CAPABILITY_UNAVAILABLE);
        assertThat(failure.failure().publicCode()).isEqualTo("GRAPH_CAPABILITY_UNAVAILABLE");
        assertThat(failure.failure().diagnostic()).doesNotContain("sk-secret-123")
                .contains("[REDACTED]");
        assertThat(failure).hasMessage("GRAPH_CAPABILITY_UNAVAILABLE");
        assertThat(new NullPointerException()).isNotInstanceOf(GraphProjectionException.class);
        assertThat(new GraphProjectionFailure(GraphProjectionFailureType.BACKEND_FAILURE,
                "MATCH (node) RETURN node from /Users/private/graph.db").diagnostic())
                .isEqualTo("graph projection operation failed");
    }

    private static GraphEntity entity(GraphWorkspaceScope workspace, String key, String name,
                                      GraphProjectionVersion version) {
        GraphAuthorityReference authority = new GraphAuthorityReference(workspace,
                GraphAuthorityKind.CANONICAL_METADATA, "metadata-" + key);
        GraphProvenance provenance = GraphProvenance.of(authority, GraphFreshness.contentHash(HASH));
        return new GraphEntity(GraphEntityIdentity.of(workspace, GraphEntityType.CONCEPT, key),
                name, provenance, GraphMetadata.empty(), version);
    }
}
