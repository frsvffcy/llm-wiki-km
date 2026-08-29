package org.km.llmwiki.search;

import java.text.Normalizer;
import java.util.Objects;

/**
 * The searchable projection of a published Wiki page.
 *
 * <p>The projection intentionally carries only data needed by FTS. The published Markdown and
 * {@code knowledge_page} row remain the source of truth. A non-published page cannot be indexed.
 */
public record KnowledgeSearchDocument(
        long workspaceId,
        String knowledgeId,
        String title,
        String normalizedTitle,
        String content,
        String markdownPath,
        String pageType,
        String pageStatus,
        String contentHash
) {
    public KnowledgeSearchDocument {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
        knowledgeId = requireText(knowledgeId, "knowledgeId");
        title = normalize(requireText(title, "title"));
        normalizedTitle = normalize(requireText(normalizedTitle, "normalizedTitle"));
        content = normalize(Objects.requireNonNull(content, "content must not be null"));
        markdownPath = requireText(markdownPath, "markdownPath");
        pageType = requireText(pageType, "pageType");
        pageStatus = requireText(pageStatus, "pageStatus");
        if (!"PUBLISHED".equals(pageStatus)) {
            throw new IllegalArgumentException("Only PUBLISHED Wiki pages may be indexed");
        }
        contentHash = requireText(contentHash, "contentHash");
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
