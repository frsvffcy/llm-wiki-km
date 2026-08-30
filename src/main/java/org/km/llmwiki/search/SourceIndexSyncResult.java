package org.km.llmwiki.search;

/** Outcome for a document or single-Source-Chunk projection refresh. */
public record SourceIndexSyncResult(SourceIndexSyncStatus status, long workspaceId, long documentId,
                                    Long sourceChunkId, int eligibleChunkCount,
                                    int indexedChunkCount, String detail) {
}
