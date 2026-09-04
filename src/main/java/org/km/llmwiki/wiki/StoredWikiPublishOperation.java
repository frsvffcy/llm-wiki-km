package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;

/** Persisted recovery boundary shared by CREATE, MERGE, and later STORY-407 hardening. */
public record StoredWikiPublishOperation(long id, long workspaceId, long draftId, long proposalId,
                                         LlmProposalAction action, String knowledgeId, String targetPath,
                                         String beforeContentHash, String contentHash, int revision,
                                         WikiPublishOperationStatus status, Long knowledgePageId,
                                         String failureDetail, String createdAt, String updatedAt,
                                         String completedAt) {
    public StoredWikiPublishOperation {
        if (id <= 0 || workspaceId <= 0 || draftId <= 0 || proposalId <= 0
                || action == null || knowledgeId == null || targetPath == null || contentHash == null
                || status == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Stored Wiki publish operation is incomplete");
        }
        boolean create = action == LlmProposalAction.CREATE && beforeContentHash == null && revision == 1;
        boolean merge = action == LlmProposalAction.MERGE && beforeContentHash != null && revision >= 2;
        if (!create && !merge) {
            throw new IllegalArgumentException("Stored Wiki publish operation action, hash, and revision disagree");
        }
    }
}
