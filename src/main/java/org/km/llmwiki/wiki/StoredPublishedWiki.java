package org.km.llmwiki.wiki;

/** Published Wiki metadata read from the durable knowledge_page authority. */
public record StoredPublishedWiki(long id, long workspaceId, String knowledgeId, String title,
                                  String normalizedTitle, WikiPageType pageType, String markdownPath,
                                  PageStatus status, String contentHash, int revision,
                                  String createdAt, String updatedAt) {

    public StoredPublishedWiki {
        if (id <= 0 || workspaceId <= 0 || knowledgeId == null || knowledgeId.isBlank()
                || title == null || title.isBlank() || normalizedTitle == null || normalizedTitle.isBlank()
                || pageType == null || markdownPath == null || markdownPath.isBlank()
                || status != PageStatus.PUBLISHED || contentHash == null || contentHash.isBlank()
                || revision < 1 || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Stored published Wiki metadata is incomplete");
        }
    }
}
