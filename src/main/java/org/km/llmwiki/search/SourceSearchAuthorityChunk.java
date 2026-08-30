package org.km.llmwiki.search;

/** Canonical Source Chunk fields considered by the eligibility boundary. Raw content is excluded. */
public record SourceSearchAuthorityChunk(long sourceChunkId, int chunkNo, Integer pageNo,
                                         String section, String headingPath,
                                         String normalizedContent, String contentHash) {
}
