package org.km.llmwiki.wiki;

/** Provenance returned for both the first CREATE and a verified repeat NO_OP. */
public record WikiCreatePublishResponse(WikiPublishOutcome outcome, long operationId, long workspaceId,
                                        long proposalId, long draftId, long knowledgePageId,
                                        String knowledgeId, String targetPath, String contentHash,
                                        int revision, String publishedAt) implements WikiPublishResult {
    public static WikiCreatePublishResponse from(WikiPublishOutcome outcome,
                                                 StoredWikiPublishOperation operation) {
        if (operation.action() != org.km.llmwiki.ai.LlmProposalAction.CREATE
                || operation.status() != WikiPublishOperationStatus.COMPLETED
                || operation.knowledgePageId() == null || operation.completedAt() == null) {
            throw new IllegalArgumentException("Only a completed CREATE publish can produce a response");
        }
        return new WikiCreatePublishResponse(outcome, operation.id(), operation.workspaceId(),
                operation.proposalId(), operation.draftId(), operation.knowledgePageId(), operation.knowledgeId(),
                operation.targetPath(), operation.contentHash(), operation.revision(), operation.completedAt());
    }
}
