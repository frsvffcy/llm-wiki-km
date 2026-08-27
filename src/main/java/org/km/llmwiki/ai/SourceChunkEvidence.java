package org.km.llmwiki.ai;

import java.util.Objects;

/** Traceable source content supplied to an LLM for analysis. */
public record SourceChunkEvidence(long sourceChunkId, int chunkNo, String contentHash, String content) {

    public SourceChunkEvidence {
        if (sourceChunkId <= 0) {
            throw new IllegalArgumentException("sourceChunkId must be positive");
        }
        if (chunkNo < 0) {
            throw new IllegalArgumentException("chunkNo must not be negative");
        }
        contentHash = required(contentHash, "contentHash");
        content = required(content, "content");
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
