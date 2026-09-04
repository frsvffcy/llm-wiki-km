package org.km.llmwiki.wiki;

/** A normalized render-ready Markdown section. */
public record WikiDraftSection(String heading, String content) {
    public WikiDraftSection {
        if (heading == null || heading.isBlank() || content == null || content.isBlank()) {
            throw new IllegalArgumentException("WikiDraft section heading and content must not be blank");
        }
    }
}
