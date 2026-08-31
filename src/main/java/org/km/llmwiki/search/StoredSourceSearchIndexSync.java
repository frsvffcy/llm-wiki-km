package org.km.llmwiki.search;

/** Durable document-level Source FTS synchronization ledger row. */
public record StoredSourceSearchIndexSync(long workspaceId, long documentId,
                                          SourceSearchIndexSyncStatus status,
                                          int eligibleChunkCount, int indexedChunkCount,
                                          String canonicalFingerprint, String indexedFingerprint,
                                          String projectionVersion, String failureDetail,
                                          String updatedAt) {
}
