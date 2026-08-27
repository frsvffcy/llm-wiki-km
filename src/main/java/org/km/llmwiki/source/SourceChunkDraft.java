package org.km.llmwiki.source;

record SourceChunkDraft(int chunkNo, Integer pageNo, String section, String headingPath,
                        String content, String normalizedContent, String contentHash) {
}
