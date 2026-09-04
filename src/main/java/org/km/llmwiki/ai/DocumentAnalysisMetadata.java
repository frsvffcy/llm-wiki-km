package org.km.llmwiki.ai;

import java.util.Objects;

/** Stable document identifiers and metadata needed for analysis provenance. */
public record DocumentAnalysisMetadata(long documentId, String originalFileName, String mimeType,
                                       String contentHash) {

    public DocumentAnalysisMetadata {
        if (documentId <= 0) {
            throw new IllegalArgumentException("documentId must be positive");
        }
        originalFileName = required(originalFileName, "originalFileName");
        mimeType = required(mimeType, "mimeType");
        contentHash = required(contentHash, "contentHash");
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
