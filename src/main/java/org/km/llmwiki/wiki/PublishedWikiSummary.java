package org.km.llmwiki.wiki;

/** Operator-safe summary of one Published Wiki page (no filesystem paths). */
public record PublishedWikiSummary(long id, String knowledgeId, String title, WikiPageType pageType,
                                   int revision, String contentHash, String updatedAt) {

    public static PublishedWikiSummary from(StoredPublishedWiki page) {
        return new PublishedWikiSummary(page.id(), page.knowledgeId(), page.title(),
                page.pageType(), page.revision(), page.contentHash(), page.updatedAt());
    }
}
