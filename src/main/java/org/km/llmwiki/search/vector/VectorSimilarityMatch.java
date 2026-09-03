package org.km.llmwiki.search.vector;

import org.km.llmwiki.search.embedding.EmbeddingEvidenceKind;

/** Bounded KNN row with normalized similarity and projection metadata for authority revalidation. */
public record VectorSimilarityMatch(EmbeddingEvidenceKind evidenceKind,
                                    String stableId,
                                    String canonicalContentHash,
                                    String embeddingProvider,
                                    String embeddingModel,
                                    int dimension,
                                    String projectionVersion,
                                    double similarity) {

    public VectorSimilarityMatch {
        if (evidenceKind == null || stableId == null || stableId.isBlank()
                || canonicalContentHash == null || !canonicalContentHash.matches("(?i)[0-9a-f]{64}")
                || embeddingProvider == null || embeddingProvider.isBlank()
                || embeddingModel == null || embeddingModel.isBlank() || dimension <= 0
                || projectionVersion == null || projectionVersion.isBlank()
                || !Double.isFinite(similarity) || similarity < 0.0d || similarity > 1.0d) {
            throw new IllegalArgumentException("Vector similarity match is invalid");
        }
        stableId = stableId.trim();
        canonicalContentHash = canonicalContentHash.toLowerCase(java.util.Locale.ROOT);
        embeddingProvider = embeddingProvider.trim();
        embeddingModel = embeddingModel.trim();
        projectionVersion = projectionVersion.trim();
    }

    public String identity() {
        return evidenceKind.name() + ":" + stableId;
    }
}
