package org.km.llmwiki.wiki;

/** Render-ready provenance copied from a validated Source Chunk. */
public record WikiDraftEvidence(long sourceChunkId, int chunkNo, Integer pageNo,
                                String section, String headingPath, String excerpt) {
    public WikiDraftEvidence {
        if (sourceChunkId <= 0 || chunkNo <= 0 || excerpt == null || excerpt.isBlank()) {
            throw new IllegalArgumentException("WikiDraft evidence requires positive ids and a non-blank excerpt");
        }
        if (pageNo != null && pageNo <= 0) {
            throw new IllegalArgumentException("WikiDraft evidence pageNo must be positive when present");
        }
    }
}
