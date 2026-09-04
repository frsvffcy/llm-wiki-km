package org.km.llmwiki.search.embedding;

/** Versioned application contract for the rebuildable embedding projection. */
public final class EmbeddingProjectionContract {

    public static final String VERSION = "embedding-projection-v1";
    public static final String VECTOR_ENCODING = "FLOAT64_LE";

    private EmbeddingProjectionContract() {
    }
}
