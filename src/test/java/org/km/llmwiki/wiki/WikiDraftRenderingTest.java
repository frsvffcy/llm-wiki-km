package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.LlmProposalAction;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WikiDraftRenderingTest {

    @Test
    void rendersIdenticalStructuredInputAsStableMarkdown() {
        WikiDraft draft = draft();
        WikiDraftMarkdownRenderer renderer = new WikiDraftMarkdownRenderer();

        String first = renderer.render(draft);
        String second = renderer.render(draft);

        assertThat(first).isEqualTo(second)
                .contains("title: \"Deterministic Draft\"")
                .contains("## Summary\n\nStable content")
                .contains("[[Target|Related]]")
                .endsWith("  > Evidence excerpt\n");
        assertThat(WikiContentHash.sha256(first)).isEqualTo(WikiContentHash.sha256(second));
    }

    @Test
    void rendersAuditableDeterministicUnifiedDiff() {
        WikiDraftDiffRenderer renderer = new WikiDraftDiffRenderer();

        String diff = renderer.render("vault/concepts/topic.md", "# Old\nkeep\n", "# New\nkeep\n");

        assertThat(diff).isEqualTo("""
                --- a/vault/concepts/topic.md
                +++ b/vault/concepts/topic.md
                @@ -1,2 +1,2 @@
                -# Old
                +# New
                 keep
                """);
        assertThat(renderer.render("vault/concepts/topic.md", "# Old\nkeep\n", "# New\nkeep\n"))
                .isEqualTo(diff);
    }

    @Test
    void enforcesTerminalLifecycleAndPublishReadiness() {
        assertThat(WikiDraftStatus.DRAFT.canTransitionTo(WikiDraftStatus.READY)).isTrue();
        assertThat(WikiDraftStatus.READY.publishReady()).isTrue();
        assertThat(WikiDraftStatus.INVALIDATED.publishReady()).isFalse();
        assertThatThrownBy(() -> WikiDraftStatus.INVALIDATED.requireTransitionTo(WikiDraftStatus.READY))
                .isInstanceOf(WikiDraftLifecycleException.class);
        assertThatThrownBy(() -> WikiDraftStatus.PUBLISHED.requireTransitionTo(WikiDraftStatus.READY))
                .isInstanceOf(WikiDraftLifecycleException.class);
    }

    private static WikiDraft draft() {
        List<Long> ids = List.of(7L);
        return new WikiDraft(11L, LlmProposalAction.CREATE, WikiPageType.CONCEPT, "Deterministic Draft",
                WikiDraftTarget.createNew("vault/concepts/deterministic-draft.md"),
                new WikiDraftFrontmatter("Deterministic Draft", WikiPageType.CONCEPT, "Stable summary",
                        List.of("stable"), List.of(), List.of(3L), ids),
                List.of(new WikiDraftSection("Summary", "Stable content")),
                List.of(new WikiDraftWikilink("Target", "Related")),
                List.of(new WikiDraftEvidence(7L, 1, null, null, null, "Evidence excerpt")), ids,
                new WikiDraftContentContract("wiki-draft/v1", List.of("title"),
                        List.of("Summary"), true, true));
    }
}
