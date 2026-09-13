package org.km.llmwiki.wiki;

/**
 * One Published Wiki page for reading: canonical metadata plus the hash-validated body
 * content (front matter stripped). Markdown is rendered by the Browser as inert text.
 */
public record PublishedWikiPage(long id, String knowledgeId, String title, WikiPageType pageType,
                                int revision, String contentHash, String updatedAt, String markdown) {

    public static PublishedWikiPage of(StoredPublishedWiki page, String searchableContent) {
        return new PublishedWikiPage(page.id(), page.knowledgeId(), page.title(), page.pageType(),
                page.revision(), page.contentHash(), page.updatedAt(), searchableContent);
    }
}
