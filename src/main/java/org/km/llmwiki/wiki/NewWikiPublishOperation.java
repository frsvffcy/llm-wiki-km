package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;

/** Immutable reservation for one human-explicit CREATE or MERGE publish attempt. */
public record NewWikiPublishOperation(long workspaceId, long draftId, long proposalId, LlmProposalAction action,
                                      String knowledgeId, String targetPath, String beforeContentHash,
                                      String contentHash, int revision, String createdAt) {
    public NewWikiPublishOperation {
        if (workspaceId <= 0 || draftId <= 0 || proposalId <= 0 || action == null
                || knowledgeId == null || knowledgeId.isBlank()
                || targetPath == null || targetPath.isBlank() || contentHash == null || contentHash.isBlank()
                || createdAt == null || createdAt.isBlank()) {
            throw new IllegalArgumentException("Wiki publish reservation is incomplete");
        }
        boolean create = action == LlmProposalAction.CREATE && beforeContentHash == null && revision == 1;
        boolean merge = action == LlmProposalAction.MERGE && beforeContentHash != null
                && beforeContentHash.matches("[0-9a-f]{64}") && revision >= 2;
        if (!create && !merge) {
            throw new IllegalArgumentException("Wiki publish reservation action, hash, and revision disagree");
        }
    }
}
