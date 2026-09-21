package org.km.llmwiki.rag;

/** Provider-neutral request for one deterministic three-modality fusion retrieval. */
public record FusedEvidenceRequest(String query, Integer maxItems, Integer maxCharacters,
                                   boolean includeGraph, Long documentId) {

    public FusedEvidenceRequest(String query, Integer maxItems, Integer maxCharacters,
                                boolean includeGraph) {
        this(query, maxItems, maxCharacters, includeGraph, null);
    }

    public FusedEvidenceRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Fusion query is required");
        }
        if (documentId != null && documentId <= 0) {
            throw new IllegalArgumentException("Fusion documentId must be positive");
        }
    }

    public static FusedEvidenceRequest of(String query) {
        return new FusedEvidenceRequest(query, null, null, true, null);
    }

    public static FusedEvidenceRequest of(String query, Integer maxItems, Integer maxCharacters,
                                          boolean includeGraph) {
        return new FusedEvidenceRequest(query, maxItems, maxCharacters, includeGraph, null);
    }

    public static FusedEvidenceRequest scoped(String query, Integer maxItems,
                                              Integer maxCharacters, long documentId) {
        return new FusedEvidenceRequest(query, maxItems, maxCharacters, false, documentId);
    }
}
