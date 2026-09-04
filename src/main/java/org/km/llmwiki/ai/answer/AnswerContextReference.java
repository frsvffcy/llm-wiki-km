package org.km.llmwiki.ai.answer;

/** Stable, content-addressed reference used to carry evidence provenance across the boundary. */
public record AnswerContextReference(String stableId, String contentHash) {

    public AnswerContextReference {
        if (stableId == null || stableId.isBlank()) {
            throw new IllegalArgumentException("context stableId must not be blank");
        }
        if (stableId.length() > 256) {
            throw new IllegalArgumentException("context stableId must not exceed 256 characters");
        }
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("context contentHash must not be blank");
        }
        if (contentHash.length() > 128) {
            throw new IllegalArgumentException("context contentHash must not exceed 128 characters");
        }
    }
}
