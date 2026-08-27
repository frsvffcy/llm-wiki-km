package org.km.llmwiki.ai;

import java.util.Objects;

/** A result claim's citation back to one source chunk. */
public record AnalysisEvidence(long sourceChunkId, String quote) {

    public AnalysisEvidence {
        if (sourceChunkId <= 0) {
            throw new IllegalArgumentException("sourceChunkId must be positive");
        }
        Objects.requireNonNull(quote, "quote must not be null");
        if (quote.isBlank()) {
            throw new IllegalArgumentException("quote must not be blank");
        }
    }
}
