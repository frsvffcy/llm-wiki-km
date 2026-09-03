package org.km.llmwiki.search.vector;

import org.km.llmwiki.search.embedding.EmbeddingEvidenceKind;

import java.util.List;
import java.util.Objects;

/**
 * Provider- and extension-neutral bounded KNN request.
 *
 * <p>The storage adapter owns how these filters are translated to its native query language.
 * Callers never provide candidate vectors or table names.
 */
public record VectorSimilarityQuery(long workspaceId,
                                    List<EmbeddingEvidenceKind> evidenceKinds,
                                    String embeddingProvider,
                                    String embeddingModel,
                                    int dimension,
                                    String projectionVersion,
                                    List<Double> queryVector,
                                    int limit,
                                    int offset,
                                    boolean freshOnly) {

    public static final int MAX_LIMIT = 200;
    public static final int MAX_OFFSET = 200;

    public VectorSimilarityQuery {
        if (workspaceId <= 0 || evidenceKinds == null || evidenceKinds.isEmpty()
                || evidenceKinds.stream().anyMatch(Objects::isNull)
                || embeddingProvider == null || embeddingProvider.isBlank()
                || embeddingModel == null || embeddingModel.isBlank()
                || dimension <= 0 || projectionVersion == null || projectionVersion.isBlank()
                || queryVector == null || queryVector.size() != dimension
                || queryVector.stream().anyMatch(value -> value == null || !Double.isFinite(value))
                || limit < 1 || limit > MAX_LIMIT || offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException("Vector similarity query is invalid");
        }
        if (evidenceKinds.stream().distinct().count() != evidenceKinds.size()) {
            throw new IllegalArgumentException("Vector similarity query contains duplicate evidence kinds");
        }
        evidenceKinds = List.copyOf(evidenceKinds);
        queryVector = List.copyOf(queryVector);
        embeddingProvider = embeddingProvider.trim();
        embeddingModel = embeddingModel.trim();
        projectionVersion = projectionVersion.trim();
    }
}
