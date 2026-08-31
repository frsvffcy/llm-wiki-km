package org.km.llmwiki.search;

/** Raw workspace-scoped Source FTS row used only by drift detection. */
record SourceIndexProjection(long rowId, boolean identityValid, String sourceChunkId, long documentId,
                             int chunkNo, Integer pageNo, String normalizedContent, String section,
                             String headingPath, String contentHash) {
}
