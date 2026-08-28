package org.km.llmwiki.wiki;

import java.util.List;

/** Controlled frontmatter fields; arbitrary LLM-provided keys are never passed through. */
public record WikiDraftFrontmatter(String title, WikiPageType pageType, String summary,
                                   List<String> tags, List<String> aliases,
                                   List<Long> sourceDocumentIds, List<Long> sourceChunkIds) {

    public WikiDraftFrontmatter {
        if (title == null || title.isBlank() || pageType == null || summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("WikiDraft frontmatter requires title, pageType, and summary");
        }
        tags = List.copyOf(tags == null ? List.of() : tags);
        aliases = List.copyOf(aliases == null ? List.of() : aliases);
        sourceDocumentIds = List.copyOf(sourceDocumentIds == null ? List.of() : sourceDocumentIds);
        sourceChunkIds = List.copyOf(sourceChunkIds == null ? List.of() : sourceChunkIds);
        if (sourceDocumentIds.isEmpty() || sourceChunkIds.isEmpty()) {
            throw new IllegalArgumentException("WikiDraft frontmatter requires document and source chunk provenance");
        }
    }
}
