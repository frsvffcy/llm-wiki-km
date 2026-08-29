package org.km.llmwiki.wiki;

/** Persisted recovery boundary shared by STORY-405 and the later STORY-407 hardening. */
public record StoredWikiPublishOperation(long id, long workspaceId, long draftId, long proposalId,
                                         String knowledgeId, String targetPath, String contentHash, int revision,
                                         WikiPublishOperationStatus status, Long knowledgePageId,
                                         String failureDetail, String createdAt, String updatedAt,
                                         String completedAt) {
    public StoredWikiPublishOperation {
        if (id <= 0 || workspaceId <= 0 || draftId <= 0 || proposalId <= 0
                || knowledgeId == null || targetPath == null || contentHash == null || revision != 1
                || status == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Stored CREATE publish operation is incomplete");
        }
    }
}
