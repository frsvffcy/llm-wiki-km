package org.km.llmwiki.wiki;

import java.util.List;

/**
 * Immutable domain representation of a Wiki page.
 *
 * <p>A {@code WikiPage} captures the structural metadata needed to describe,
 * validate, and (in later Sprints) persist or publish a personal knowledge page.
 * It does <strong>not</strong> carry raw Markdown content — that belongs to the
 * publish pipeline (STORY-402 onward).
 *
 * <p>The {@code logicalRelativePath} follows the vault path contract enforced by
 * {@link WikiPathContract} and is always workspace-relative, e.g.
 * {@code "vault/concepts/spring-boot-3.md"}.
 *
 * @param title               human-readable page title
 * @param pageType            controlled page type (see {@link WikiPageType})
 * @param logicalRelativePath workspace-relative vault path, validated by {@link WikiPathContract}
 * @param summary             optional one-sentence summary of the page
 * @param tags                immutable list of tag strings
 * @param aliases             immutable list of alternative titles / aliases
 * @param sourceDocumentIds   immutable list of source {@code document.id} values
 *                            that contributed to this page (Many-to-Many provenance)
 */
public record WikiPage(
        String title,
        WikiPageType pageType,
        String logicalRelativePath,
        String summary,
        List<String> tags,
        List<String> aliases,
        List<Long> sourceDocumentIds
) {
    /**
     * Compact constructor that defensively copies mutable collections and
     * validates the mandatory fields.
     */
    public WikiPage {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("WikiPage title must not be null or blank");
        }
        if (pageType == null) {
            throw new IllegalArgumentException("WikiPage pageType must not be null");
        }
        if (logicalRelativePath == null || logicalRelativePath.isBlank()) {
            throw new IllegalArgumentException("WikiPage logicalRelativePath must not be null or blank");
        }
        tags = tags == null ? List.of() : List.copyOf(tags);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        sourceDocumentIds = sourceDocumentIds == null ? List.of() : List.copyOf(sourceDocumentIds);
    }
}
