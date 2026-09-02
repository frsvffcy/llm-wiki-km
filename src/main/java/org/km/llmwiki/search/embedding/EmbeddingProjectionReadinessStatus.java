package org.km.llmwiki.search.embedding;

public enum EmbeddingProjectionReadinessStatus {
    NOT_BUILT, QUEUED, REBUILDING, PARTIAL, STALE, FAILED, READY
}
