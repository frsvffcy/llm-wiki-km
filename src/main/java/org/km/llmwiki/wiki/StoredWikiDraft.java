package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;

/** Immutable jOOQ-backed review snapshot for one Wiki Draft. */
public record StoredWikiDraft(long id, long workspaceId, long proposalId, LlmProposalAction action,
                              WikiPageType pageType, String title, String targetTitle, WikiPageType targetPageType,
                              String targetKnowledgeId,
                              String targetPath, WikiDraftStatus status, String expectedContentHash,
                              String baseContentHash, String renderedContentHash, String inputHash,
                              String structuredDraftJson, String baseContent, String renderedContent,
                              WikiDraftInvalidationReason invalidatedReason, Long regeneratedFromDraftId,
                              String createdAt, String updatedAt) {

    public StoredWikiDraft {
        if (id <= 0 || workspaceId <= 0 || proposalId <= 0 || action == null || pageType == null
                || title == null || title.isBlank() || targetTitle == null || targetTitle.isBlank()
                || targetPageType == null || targetPath == null || targetPath.isBlank()
                || status == null || baseContentHash == null || renderedContentHash == null
                || inputHash == null || structuredDraftJson == null || baseContent == null
                || renderedContent == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Stored Wiki Draft is incomplete");
        }
        if ((status == WikiDraftStatus.INVALIDATED) != (invalidatedReason != null)) {
            throw new IllegalArgumentException("Stored Wiki Draft invalidation fields are inconsistent");
        }
    }

    public boolean publishReady() {
        return status.publishReady();
    }
}
