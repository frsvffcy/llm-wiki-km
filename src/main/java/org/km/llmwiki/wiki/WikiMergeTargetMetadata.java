package org.km.llmwiki.wiki;

/** Current jOOQ-backed metadata for the unique existing page authorized by a MERGE Draft. */
record WikiMergeTargetMetadata(long id, long workspaceId, String knowledgeId, String title,
                               WikiPageType pageType, String targetPath, PageStatus status,
                               String contentHash, int revision, String createdAt) {

    WikiMergeTargetMetadata {
        if (id <= 0 || workspaceId <= 0 || knowledgeId == null || knowledgeId.isBlank()
                || title == null || title.isBlank() || pageType == null || targetPath == null
                || targetPath.isBlank() || status == null || contentHash == null
                || !contentHash.matches("[0-9a-f]{64}") || revision < 1
                || createdAt == null || createdAt.isBlank()) {
            throw new IllegalArgumentException("MERGE target metadata is incomplete");
        }
    }
}
