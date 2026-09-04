package org.km.llmwiki.search.embedding;

/** Outcome of one authoritative-content projection attempt. */
public enum EmbeddingProjectionOperationStatus {
    FRESH,
    FAILED,
    NOT_FOUND,
    INELIGIBLE
}
