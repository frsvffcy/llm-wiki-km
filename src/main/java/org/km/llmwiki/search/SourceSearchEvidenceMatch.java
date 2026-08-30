package org.km.llmwiki.search;

/** Searchable Source projection with enough provenance to reload authoritative evidence. */
public record SourceSearchEvidenceMatch(long sourceChunkId, long workspaceId, long documentId,
                                        int chunkNo, Integer pageNo, String section,
                                        String headingPath, String normalizedContent,
                                        String contentHash, double rank) {
}
