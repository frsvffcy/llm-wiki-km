package org.km.llmwiki.ai.embedding;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable provider-neutral embedding result. */
public record EmbeddingResult(
        List<EmbeddingVector> vectors,
        EmbeddingProviderMetadata providerMetadata,
        Optional<EmbeddingUsageMetadata> usage
) {

    public EmbeddingResult {
        if (vectors == null || vectors.isEmpty() || vectors.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("vectors must not be empty or contain null values");
        }
        vectors = List.copyOf(vectors);
        providerMetadata = Objects.requireNonNull(providerMetadata,
                "providerMetadata must not be null");
        usage = usage == null ? Optional.empty() : usage;
        int dimension = vectors.getFirst().values().size();
        if (vectors.stream().anyMatch(vector -> vector.values().size() != dimension)) {
            throw new IllegalArgumentException("all embedding vectors must have one dimension");
        }
    }

    public int dimension() {
        return vectors.getFirst().values().size();
    }
}
