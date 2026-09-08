package org.km.llmwiki.persistence.graph;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.graph.GraphAuthorityKind;
import org.km.llmwiki.graph.GraphAuthorityReference;
import org.km.llmwiki.graph.GraphEntity;
import org.km.llmwiki.graph.GraphEntityIdentity;
import org.km.llmwiki.graph.GraphEntityType;
import org.km.llmwiki.graph.GraphFreshness;
import org.km.llmwiki.graph.GraphMetadata;
import org.km.llmwiki.graph.GraphProvenance;
import org.km.llmwiki.graph.GraphWorkspaceScope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class CanonicalWikiRelationEvidenceTest {

    private static final String HASH = "a".repeat(64);

    @Test
    void parsesOnlyControlledStructuredEvidenceDeterministically() {
        var evidence = CanonicalWikiRelationEvidence.parse("""
                ---
                id: "wiki-1"
                tags:
                  - " RAG "
                  - "rag"
                sources:
                  - "document:42"
                ---

                # Source
                普通文字提到 Target 不構成 MENTIONS。
                - [[ Target  Page |顯示名稱]]
                - [[Target Page]]
                """);
        assertThat(evidence.tags()).containsExactly("rag");
        assertThat(evidence.sourceDocumentIds()).containsExactly(42L);
        assertThat(evidence.normalizedLinkTargets()).containsExactly("target page");
    }

    @Test
    void rejectsMalformedControlledFieldsInsteadOfGuessing() {
        assertThatThrownBy(() -> CanonicalWikiRelationEvidence.parse("""
                ---
                tags:
                  - unquoted
                sources: []
                ---

                # Invalid
                """)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalWikiRelationEvidence.parse("""
                ---
                tags: []
                sources:
                  - "https://example.invalid/source"
                ---

                # Invalid
                """)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalWikiRelationEvidence.parse("""
                ---
                tags:
                   - "wrong-indentation"
                sources: []
                ---

                # Invalid
                """)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalWikiRelationEvidence.parse("""
                ---
                tags: []
                tags: []
                sources: []
                ---

                # Duplicate field
                """)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalWikiRelationEvidence.parse("""
                ---
                tags: ["inline-is-not-controlled"]
                sources: []
                ---

                # Inline list
                """)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalWikiRelationEvidence.parse("""
                ---
                tags:
                  - "unsupported\\tescape"
                sources: []
                ---

                # Unsupported escape
                """)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalWikiRelationEvidence.parse("""
                ---
                tags:
                  - "split\\nvalue"
                sources: []
                ---

                # Multiline tag
                """)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPerPageEvidenceBeyondTheHardCap() {
        StringBuilder markdown = new StringBuilder("---\ntags:\n");
        for (int index = 0; index <= 2_000; index++) {
            markdown.append("  - \"tag-").append(index).append("\"\n");
        }
        markdown.append("sources: []\n---\n\n# Too many tags\n");

        assertThatThrownBy(() -> CanonicalWikiRelationEvidence.parse(markdown.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repeatedWikiLinksCannotBypassThePerPageEvidenceHardCap() {
        StringBuilder markdown = new StringBuilder("---\ntags: []\nsources: []\n---\n\n# Too many links\n");
        for (int index = 0; index <= 2_000; index++) {
            markdown.append("[[Same Target]]\n");
        }

        assertThatThrownBy(() -> CanonicalWikiRelationEvidence.parse(markdown.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ambiguousNormalizedWikiTitlesAreExcludedFromResolution() {
        var first = wikiEntity("wiki-a", "Target Page");
        var second = wikiEntity("wiki-b", "target   page");
        var unique = wikiEntity("wiki-c", "Unique Target");

        var index = CanonicalGraphProjectionInputAssembler.uniqueWikiTitleIndex(
                java.util.List.of(second, unique, first));

        assertThat(index).doesNotContainKey("target page")
                .containsEntry("unique target", unique);
    }

    private static GraphEntity wikiEntity(String id, String title) {
        var workspace = new GraphWorkspaceScope(21);
        var authority = new GraphAuthorityReference(workspace, GraphAuthorityKind.WIKI_PAGE, id);
        return GraphEntity.of(GraphEntityIdentity.fromAuthority(authority, GraphEntityType.WIKI_PAGE),
                title, GraphProvenance.of(authority, GraphFreshness.contentHash(HASH)),
                GraphMetadata.empty());
    }
}
