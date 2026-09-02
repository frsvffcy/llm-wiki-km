package org.km.llmwiki.rag;

/** Independent retrieval algorithm selection; corpus selection remains on {@link RetrievalMode}. */
public enum RetrievalStrategy {
    LEXICAL,
    SEMANTIC,
    HYBRID
}
