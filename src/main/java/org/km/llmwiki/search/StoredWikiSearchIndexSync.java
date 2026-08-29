package org.km.llmwiki.search;

/** Durable sync ledger row for one workspace-scoped Published Wiki identity. */
public record StoredWikiSearchIndexSync(long workspaceId, long knowledgePageId, String knowledgeId,
                                        WikiSearchIndexSyncStatus status, String contentHash,
                                        String indexedContentHash, String failureDetail, String updatedAt) {
}
