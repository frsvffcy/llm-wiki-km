package org.km.llmwiki.wiki;

/** 提供人工審核的來源 Chunk 證據。 */
public record KnowledgeProposalEvidence(long sourceChunkId, int chunkNo, Integer pageNo, String section,
                                        String headingPath, String content) {
}
