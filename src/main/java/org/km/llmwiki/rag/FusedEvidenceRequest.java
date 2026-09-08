package org.km.llmwiki.rag;

/** Provider-neutral request for one deterministic three-modality fusion retrieval. */
public record FusedEvidenceRequest(String query, Integer maxItems, Integer maxCharacters,
                                   boolean includeGraph) {

    public FusedEvidenceRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Fusion query is required");
        }
    }

    public static FusedEvidenceRequest of(String query) {
        return new FusedEvidenceRequest(query, null, null, true);
    }

    public static FusedEvidenceRequest of(String query, Integer maxItems, Integer maxCharacters,
                                          boolean includeGraph) {
        return new FusedEvidenceRequest(query, maxItems, maxCharacters, includeGraph);
    }
}
