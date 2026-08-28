package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;

/** Values persisted while creating one reviewable Wiki Draft snapshot. */
record NewStoredWikiDraft(long workspaceId, long proposalId, LlmProposalAction action,
                          WikiPageType pageType, String title, String targetTitle, WikiPageType targetPageType,
                          String targetKnowledgeId,
                          String targetPath, String expectedContentHash, String baseContentHash,
                          String renderedContentHash, String inputHash, String structuredDraftJson,
                          String baseContent, String renderedContent, Long regeneratedFromDraftId) {
}
