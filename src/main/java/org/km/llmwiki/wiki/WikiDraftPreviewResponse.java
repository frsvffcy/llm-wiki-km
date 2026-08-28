package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;

import java.util.List;

/** Rendered review payload together with the proposal, target, and evidence provenance used to create it. */
public record WikiDraftPreviewResponse(long id, long proposalId, LlmProposalAction action, String targetPath,
                                       WikiDraftStatus status, boolean publishReady, List<Long> sourceChunkIds,
                                       List<WikiDraftEvidence> evidence, String renderedContentHash,
                                       String markdown) {

    static WikiDraftPreviewResponse from(StoredWikiDraft draft, WikiDraft structured) {
        return new WikiDraftPreviewResponse(draft.id(), draft.proposalId(), draft.action(), draft.targetPath(),
                draft.status(), draft.publishReady(), structured.sourceChunkIds(), structured.evidence(),
                draft.renderedContentHash(), draft.renderedContent());
    }
}
