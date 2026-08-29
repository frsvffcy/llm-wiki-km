package org.km.llmwiki.wiki;

/** Immutable reservation for one human-explicit CREATE publish attempt. */
public record NewWikiPublishOperation(long workspaceId, long draftId, long proposalId, String knowledgeId,
                                      String targetPath, String contentHash, int revision, String createdAt) {
    public NewWikiPublishOperation {
        if (workspaceId <= 0 || draftId <= 0 || proposalId <= 0 || knowledgeId == null || knowledgeId.isBlank()
                || targetPath == null || targetPath.isBlank() || contentHash == null || contentHash.isBlank()
                || revision != 1 || createdAt == null || createdAt.isBlank()) {
            throw new IllegalArgumentException("CREATE publish reservation is incomplete");
        }
    }
}
