package org.km.llmwiki.graph;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("contract")
class GraphDomainContractTest {

    private static final String FIRST_HASH = "a".repeat(64);
    private static final String SECOND_HASH = "b".repeat(64);
    private static final GraphProjectionVersion VERSION = GraphProjectionVersion.initial();

    @Test
    void rebuildKeepsEntityIdentityStableWhileFreshnessChanges() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(11);
        GraphAuthorityReference authority = new GraphAuthorityReference(workspace,
                GraphAuthorityKind.WIKI_PAGE, "page-1");
        GraphEntityIdentity firstIdentity = GraphEntityIdentity.fromAuthority(authority,
                GraphEntityType.WIKI_PAGE);
        GraphEntityIdentity rebuiltIdentity = GraphEntityIdentity.fromAuthority(authority,
                GraphEntityType.WIKI_PAGE);

        GraphEntity first = GraphEntity.of(firstIdentity, "Page", GraphProvenance.of(authority,
                new GraphFreshness(1, FIRST_HASH)), GraphMetadata.empty());
        GraphEntity updated = GraphEntity.of(rebuiltIdentity, "Page", GraphProvenance.of(authority,
                new GraphFreshness(2, SECOND_HASH)), GraphMetadata.empty());

        assertThat(first.identity()).isEqualTo(updated.identity());
        assertThat(first.identity().stableId()).isEqualTo(rebuiltIdentity.stableId())
                .startsWith("ge1_").hasSize(68);
        assertThat(first.provenance().freshness()).isNotEqualTo(updated.provenance().freshness());
    }

    @Test
    void scopesEntityAndRelationIdentityAndRejectsCrossWorkspaceProvenance() {
        GraphWorkspaceScope firstWorkspace = new GraphWorkspaceScope(11);
        GraphWorkspaceScope secondWorkspace = new GraphWorkspaceScope(12);
        GraphAuthorityReference firstAuthority = new GraphAuthorityReference(firstWorkspace,
                GraphAuthorityKind.WIKI_PAGE, "same-local-id");
        GraphAuthorityReference secondAuthority = new GraphAuthorityReference(secondWorkspace,
                GraphAuthorityKind.WIKI_PAGE, "same-local-id");

        GraphEntityIdentity first = GraphEntityIdentity.fromAuthority(firstAuthority,
                GraphEntityType.WIKI_PAGE);
        GraphEntityIdentity second = GraphEntityIdentity.fromAuthority(secondAuthority,
                GraphEntityType.WIKI_PAGE);

        assertThat(first.stableId()).isNotEqualTo(second.stableId());
        assertThatThrownBy(() -> GraphRelationIdentity.of(first, GraphRelationType.RELATED_TO, second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace");
        assertThatThrownBy(() -> GraphEntity.of(first, "Page", GraphProvenance.of(secondAuthority,
                GraphFreshness.contentHash(FIRST_HASH)), GraphMetadata.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace");
        assertThatThrownBy(() -> GraphEntityIdentity.fromAuthority(firstAuthority,
                GraphEntityType.SOURCE_DOCUMENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authority");
        assertThatThrownBy(() -> GraphEntity.of(GraphEntityIdentity.of(firstWorkspace,
                GraphEntityType.WIKI_PAGE, "wiki-page:other-page"), "Page",
                GraphProvenance.of(firstAuthority, GraphFreshness.contentHash(FIRST_HASH)),
                GraphMetadata.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provenance");
    }

    @Test
    void relationIdentityIsDirectedAndIndependentOfInsertOrder() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(11);
        GraphEntityIdentity source = identity(workspace, "source");
        GraphEntityIdentity target = identity(workspace, "target");
        GraphRelationIdentity first = GraphRelationIdentity.of(source, GraphRelationType.RELATED_TO, target);
        GraphRelationIdentity rebuilt = GraphRelationIdentity.of(source, GraphRelationType.RELATED_TO, target);
        GraphRelationIdentity reversed = GraphRelationIdentity.of(target, GraphRelationType.RELATED_TO, source);

        assertThat(first).isEqualTo(rebuilt);
        assertThat(first.stableId()).startsWith("gr1_").hasSize(68);
        assertThat(first.stableId()).isNotEqualTo(reversed.stableId());
    }

    @Test
    void rejectsMalformedAuthorityAndUnknownAuthorityKind() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(11);

        assertThatThrownBy(() -> GraphAuthorityReference.of(workspace, "unknown", "id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphFreshness(null, "not-a-sha"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphWorkspaceScope(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataIsTypedBoundedCanonicalAndImmutable() {
        GraphMetadata metadata = GraphMetadata.of(Map.of("z-key", "value", "a-key", "值"));

        assertThat(metadata.entries().keySet()).containsExactly("a-key", "z-key");
        assertThatThrownBy(() -> metadata.entries().put("new-key", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> GraphMetadata.of(Map.of(
                "a-key", "x".repeat(GraphMetadata.MAX_VALUE_CODE_POINTS + 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GraphMetadata.of(Map.of(
                "A-key", "value")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceLifecycleIsExplicitAndOnlyEligibleAuthorityCanEnterInput() {
        GraphWorkspaceScope workspace = new GraphWorkspaceScope(11);
        GraphAuthorityReference authority = new GraphAuthorityReference(workspace,
                GraphAuthorityKind.SOURCE_CHUNK, "chunk-1");
        GraphEntityIdentity identity = GraphEntityIdentity.fromAuthority(authority,
                GraphEntityType.SOURCE_CHUNK);
        GraphEntity deleted = new GraphEntity(identity, "Chunk", new GraphProvenance(authority,
                GraphFreshness.contentHash(FIRST_HASH), GraphAuthorityEligibility.DELETED,
                GraphMetadata.empty()), GraphMetadata.empty(), VERSION);

        assertThat(deleted.provenance().eligible()).isFalse();
        assertThatThrownBy(() -> new GraphProjectionInput(workspace, VERSION, List.of(deleted), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ineligible");
    }

    private static GraphEntityIdentity identity(GraphWorkspaceScope workspace, String key) {
        return GraphEntityIdentity.of(workspace, GraphEntityType.CONCEPT, key);
    }
}
