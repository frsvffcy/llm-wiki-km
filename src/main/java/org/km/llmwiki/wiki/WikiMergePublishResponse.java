package org.km.llmwiki.wiki;

/** Provenance returned for a successful MERGE and a verified repeat NO_OP. */
public record WikiMergePublishResponse(WikiPublishOutcome outcome, long operationId, long workspaceId,
                                       long proposalId, long draftId, long knowledgePageId,
                                       String knowledgeId, String targetPath, String beforeHash,
                                       String afterHash, String contentHash, int revision,
                                       String publishedAt) implements WikiPublishResult {

    public static WikiMergePublishResponse from(WikiPublishOutcome outcome,
                                                StoredWikiPublishOperation operation) {
        if (operation.action() != org.km.llmwiki.ai.LlmProposalAction.MERGE
                || operation.status() != WikiPublishOperationStatus.COMPLETED
                || operation.knowledgePageId() == null || operation.completedAt() == null
                || operation.beforeContentHash() == null) {
            throw new IllegalArgumentException("Only a completed MERGE publish can produce a response");
        }
        return new WikiMergePublishResponse(outcome, operation.id(), operation.workspaceId(),
                operation.proposalId(), operation.draftId(), operation.knowledgePageId(), operation.knowledgeId(),
                operation.targetPath(), operation.beforeContentHash(), operation.contentHash(),
                operation.contentHash(), operation.revision(), operation.completedAt());
    }
}
