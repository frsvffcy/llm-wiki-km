package org.km.llmwiki.search.embedding;

import java.util.Objects;

/** Complete deterministic freshness key for one workspace-scoped evidence identity. */
public record EmbeddingProjectionIdentity(long workspaceId, EmbeddingEvidenceKind evidenceKind,
                                          String stableId, String canonicalContentHash,
                                          String embeddingProvider, String embeddingModel,
                                          int dimension, String projectionVersion) {

    public EmbeddingProjectionIdentity {
        if (workspaceId <= 0 || evidenceKind == null || stableId == null || stableId.isBlank()
                || canonicalContentHash == null || !canonicalContentHash.matches("[0-9a-f]{64}")
                || embeddingProvider == null || embeddingProvider.isBlank()
                || embeddingModel == null || embeddingModel.isBlank()
                || dimension <= 0 || projectionVersion == null || projectionVersion.isBlank()) {
            throw new IllegalArgumentException("Embedding projection identity is incomplete");
        }
        stableId = stableId.trim();
        canonicalContentHash = canonicalContentHash.toLowerCase(java.util.Locale.ROOT);
        embeddingProvider = Objects.requireNonNull(embeddingProvider).trim();
        embeddingModel = Objects.requireNonNull(embeddingModel).trim();
        projectionVersion = projectionVersion.trim();
    }
}
