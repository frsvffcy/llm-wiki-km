package org.km.llmwiki.source;

public record SourceChunk(long id, long documentId, int chunkNo, Integer pageNo, String section,
                          String headingPath, String content, String normalizedContent,
                          String contentHash) {
}
