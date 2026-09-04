package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;

import java.util.List;

/** API metadata for one persisted Wiki Draft without duplicating its potentially large content. */
public record WikiDraftResponse(long id, long proposalId, LlmProposalAction action, WikiPageType pageType,
                                String title, String targetTitle, WikiPageType targetPageType,
                                String targetKnowledgeId, String targetPath,
                                WikiDraftStatus status, boolean publishReady, String expectedContentHash,
                                String baseContentHash, String renderedContentHash, String inputHash,
                                List<Long> sourceChunkIds,
                                WikiDraftInvalidationReason invalidatedReason, Long regeneratedFromDraftId,
                                String createdAt, String updatedAt) {

    static WikiDraftResponse from(StoredWikiDraft draft, WikiDraft structured) {
        return new WikiDraftResponse(draft.id(), draft.proposalId(), draft.action(), draft.pageType(),
                draft.title(), draft.targetTitle(), draft.targetPageType(), draft.targetKnowledgeId(),
                draft.targetPath(), draft.status(), draft.publishReady(),
                draft.expectedContentHash(), draft.baseContentHash(), draft.renderedContentHash(), draft.inputHash(),
                structured.sourceChunkIds(), draft.invalidatedReason(), draft.regeneratedFromDraftId(),
                draft.createdAt(), draft.updatedAt());
    }
}
