package org.km.llmwiki.rag;

/** Provider-neutral retrieval input; no prompt or model settings belong here. */
public record RetrievalRequest(String query, RetrievalMode mode,
                               Integer maxItems, Integer maxCharacters) {

    public RetrievalRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Retrieval query must not be blank");
        }
        if (mode == null) {
            throw new IllegalArgumentException("Retrieval mode is required");
        }
    }

    public static RetrievalRequest defaults(String query, RetrievalMode mode) {
        return new RetrievalRequest(query, mode, null, null);
    }
}
