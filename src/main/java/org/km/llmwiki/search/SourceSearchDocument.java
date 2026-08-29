package org.km.llmwiki.search;

import java.text.Normalizer;
import java.util.Objects;

/** Search projection for a Source Chunk. Raw content is deliberately not part of this record. */
public record SourceSearchDocument(
        long workspaceId,
        long sourceChunkId,
        long documentId,
        int chunkNo,
        String normalizedContent,
        String section,
        String headingPath,
        String contentHash
) {
    public SourceSearchDocument {
        if (workspaceId <= 0 || sourceChunkId <= 0 || documentId <= 0 || chunkNo <= 0) {
            throw new IllegalArgumentException("Source identity and chunk numbers must be positive");
        }
        normalizedContent = Normalizer.normalize(
                Objects.requireNonNull(normalizedContent, "normalizedContent must not be null"),
                Normalizer.Form.NFC);
        if (normalizedContent.isBlank()) {
            throw new IllegalArgumentException("normalizedContent must not be blank");
        }
        contentHash = requireText(contentHash, "contentHash");
    }

    public String stableId() {
        return Long.toString(sourceChunkId);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
