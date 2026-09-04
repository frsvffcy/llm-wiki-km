package org.km.llmwiki.rag;

/** Provider-neutral retrieval input; no prompt or model settings belong here. */
public record RetrievalRequest(String query, RetrievalMode mode,
                               Integer maxItems, Integer maxCharacters,
                               RetrievalStrategy strategy) {

    public RetrievalRequest(String query, RetrievalMode mode,
                            Integer maxItems, Integer maxCharacters) {
        this(query, mode, maxItems, maxCharacters, mode == null ? null : mode.strategy());
    }

    public RetrievalRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Retrieval query must not be blank");
        }
        if (mode == null) {
            throw new IllegalArgumentException("Retrieval mode is required");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("Retrieval strategy is required");
        }
    }

    public static RetrievalRequest defaults(String query, RetrievalMode mode) {
        return new RetrievalRequest(query, mode, null, null);
    }

    /** Creates an orthogonal request while retaining a compatibility mode for evidence metadata. */
    public static RetrievalRequest of(String query, RetrievalMode mode, RetrievalStrategy strategy,
                                      Integer maxItems, Integer maxCharacters) {
        return new RetrievalRequest(query, mode, maxItems, maxCharacters, strategy);
    }

    public org.km.llmwiki.search.SearchCorpus corpus() {
        return mode.searchCorpus();
    }
}
