package org.km.llmwiki.wiki;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/** Adds controlled publish metadata to the deterministic #91 Draft body. */
@Component
public class WikiPublishedMarkdownRenderer {

    private final WikiDraftMarkdownRenderer draftRenderer;

    public WikiPublishedMarkdownRenderer(WikiDraftMarkdownRenderer draftRenderer) {
        this.draftRenderer = draftRenderer;
    }

    public String render(WikiDraft draft, String knowledgeId, long draftId, int revision, String publishedAt) {
        StringBuilder markdown = new StringBuilder("---\n");
        scalar(markdown, "id", knowledgeId);
        scalar(markdown, "title", draft.title());
        scalar(markdown, "type", draft.pageType().name());
        scalar(markdown, "status", PageStatus.PUBLISHED.name());
        list(markdown, "aliases", draft.frontmatter().aliases(), WikiPublishedMarkdownRenderer::quote);
        list(markdown, "tags", draft.frontmatter().tags(), WikiPublishedMarkdownRenderer::quote);
        list(markdown, "sources", draft.frontmatter().sourceDocumentIds(), value -> quote("document:" + value));
        scalar(markdown, "created_at", publishedAt);
        scalar(markdown, "updated_at", publishedAt);
        markdown.append("proposal_id: ").append(draft.proposalId()).append('\n');
        markdown.append("draft_id: ").append(draftId).append('\n');
        markdown.append("revision: ").append(revision).append('\n');
        markdown.append("---\n\n").append(draftRenderer.renderBody(draft));
        return markdown.toString();
    }

    private static void scalar(StringBuilder markdown, String name, String value) {
        markdown.append(name).append(": ").append(quote(value)).append('\n');
    }

    private static <T> void list(StringBuilder markdown, String name, List<T> values,
                                 Function<T, String> formatter) {
        if (values.isEmpty()) {
            markdown.append(name).append(": []\n");
            return;
        }
        markdown.append(name).append(":\n");
        values.forEach(value -> markdown.append("  - ").append(formatter.apply(value)).append('\n'));
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + '"';
    }
}
