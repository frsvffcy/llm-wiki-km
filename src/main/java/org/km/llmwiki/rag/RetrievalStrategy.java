package org.km.llmwiki.rag;

/** Independent retrieval algorithm selection; corpus selection remains on {@link RetrievalMode}. */
public enum RetrievalStrategy {
    LEXICAL,
    SEMANTIC,
    HYBRID,
    /**
     * Application-owned deterministic fusion of the lexical, vector, and graph evidence
     * channels. Orchestration, traversal bounds, admission, ranking, budgets, and handoff
     * currentness belong to the application layer, never to a backend adapter.
     */
    FUSED
}
