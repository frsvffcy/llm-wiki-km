package org.km.llmwiki.search;

/** A repository-level FTS match; higher-level Search API ranking/snippet UX is out of scope here. */
public record SearchIndexMatch(
        String corpus,
        long workspaceId,
        String stableId,
        String title,
        String searchableText,
        String location,
        String pageType,
        String contentHash,
        double rank
) {
}
