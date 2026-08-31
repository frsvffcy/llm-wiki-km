package org.km.llmwiki.search;

record SourceFtsSearchMatch(long workspaceId, long sourceChunkId, long documentId,
                            String documentName, int chunkNo, Integer pageNo,
                            String section, String headingPath, String indexedContentHash,
                            String documentFingerprint, int eligibleChunkCount, String snippet,
                            double rawRank) {
}
