package org.km.llmwiki.rag;

/** Provider-neutral retrieval input; no prompt or model settings belong here. */
public record RetrievalRequest(String query, RetrievalMode mode,
                               Integer maxItems, Integer maxCharacters,
                               RetrievalStrategy strategy,
                               DocumentRetrievalScope documentScope) {

    public RetrievalRequest(String query, RetrievalMode mode, Integer maxItems,
                            Integer maxCharacters, RetrievalStrategy strategy) {
        this(query, mode, maxItems, maxCharacters, strategy, null);
    }

    public RetrievalRequest(String query, RetrievalMode mode,
                            Integer maxItems, Integer maxCharacters) {
        this(query, mode, maxItems, maxCharacters, mode == null ? null : mode.strategy(), null);
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

    public static RetrievalRequest of(String query, RetrievalMode mode, RetrievalStrategy strategy,
                                      Integer maxItems, Integer maxCharacters,
                                      DocumentRetrievalScope documentScope) {
        return new RetrievalRequest(query, mode, maxItems, maxCharacters, strategy, documentScope);
    }

    /**
     * Resolves the public retrieval contract without silently changing its meaning. An
     * unscoped request keeps the corpus declared by its mode. Once a document scope is present,
     * the scope is authoritative for corpus selection and every mode searches that one source
     * document; the mode continues to select only the retrieval strategy.
     *
     * <p>The scoped compatibility matrix is therefore:
     * WIKI_ONLY/SOURCE_ONLY/HYBRID_FTS -> SOURCE + LEXICAL,
     * SEMANTIC_WIKI/SEMANTIC_SOURCE -> SOURCE + SEMANTIC,
     * HYBRID_VECTOR -> SOURCE + HYBRID, and HYBRID_GRAPH -> SOURCE + FUSED.
     */
    public org.km.llmwiki.search.SearchCorpus resolvedCorpus() {
        return documentScope == null ? mode.searchCorpus()
                : org.km.llmwiki.search.SearchCorpus.SOURCE;
    }

    /** The effective strategy; unlike the corpus, it is never rewritten by document scope. */
    public RetrievalStrategy resolvedStrategy() {
        return strategy;
    }

    public boolean documentScoped() {
        return documentScope != null;
    }

    /** Backward-compatible alias used by retrieval implementations. */
    public org.km.llmwiki.search.SearchCorpus corpus() {
        return resolvedCorpus();
    }
}
