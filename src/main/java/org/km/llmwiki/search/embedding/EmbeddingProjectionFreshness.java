package org.km.llmwiki.search.embedding;

/** Central freshness evaluator shared by persistence and future semantic retrieval callers. */
public final class EmbeddingProjectionFreshness {

    private EmbeddingProjectionFreshness() {
    }

    public static boolean isFresh(StoredEmbeddingProjection projection,
                                  EmbeddingProjectionIdentity expected) {
        if (projection == null || expected == null
                || projection.status() != EmbeddingProjectionStatus.FRESH
                || projection.workspaceId() != expected.workspaceId()
                || projection.evidenceKind() != expected.evidenceKind()
                || !expected.stableId().equals(projection.stableId())
                || !expected.canonicalContentHash().equals(projection.canonicalContentHash())
                || !expected.embeddingProvider().equals(projection.embeddingProvider())
                || !expected.embeddingModel().equals(projection.embeddingModel())
                || projection.dimension() == null || projection.dimension() != expected.dimension()
                || !expected.projectionVersion().equals(projection.projectionVersion())
                || !EmbeddingProjectionContract.VECTOR_ENCODING.equals(projection.vectorEncoding())
                || projection.vectorBlob() == null
                || projection.generatedAt() == null
                || projection.failureType() != null
                || projection.failureDetail() != null) {
            return false;
        }
        try {
            // Decode through the same bounded domain value used at the provider boundary. This
            // rejects truncated/corrupt blobs as well as NaN and Infinity introduced by a bad
            // write or a future storage migration.
            EmbeddingVectorCodec.decode(expected.canonicalContentHash(), projection.vectorBlob(),
                    expected.dimension());
            return true;
        } catch (IllegalArgumentException | ArithmeticException failure) {
            return false;
        }
    }
}
