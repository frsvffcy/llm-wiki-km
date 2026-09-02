package org.km.llmwiki.search.embedding;

/** Persisted generation state; only FRESH rows can ever be served by a future retriever. */
public enum EmbeddingProjectionStatus {
    FRESH,
    FAILED
}
