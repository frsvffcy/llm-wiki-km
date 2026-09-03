package org.km.llmwiki.search.embedding;

import java.util.Arrays;

/** Relational projection row; it intentionally contains no provider response or credential. */
public record StoredEmbeddingProjection(long id, long workspaceId, EmbeddingEvidenceKind evidenceKind,
                                        String stableId, String canonicalContentHash,
                                        String embeddingProvider, String embeddingModel, Integer dimension,
                                        String projectionVersion, long projectionGeneration,
                                        EmbeddingProjectionStatus status,
                                        String vectorEncoding, byte[] vectorBlob, int generationAttempt,
                                        String generatedAt, String lastAttemptAt, String failureType,
                                        String failureDetail, String createdAt, String updatedAt) {

    public StoredEmbeddingProjection {
        vectorBlob = vectorBlob == null ? null : Arrays.copyOf(vectorBlob, vectorBlob.length);
    }

    /** Compatibility constructor for rows created before generation-aware projection storage. */
    public StoredEmbeddingProjection(long id, long workspaceId, EmbeddingEvidenceKind evidenceKind,
                                     String stableId, String canonicalContentHash,
                                     String embeddingProvider, String embeddingModel, Integer dimension,
                                     String projectionVersion, EmbeddingProjectionStatus status,
                                     String vectorEncoding, byte[] vectorBlob, int generationAttempt,
                                     String generatedAt, String lastAttemptAt, String failureType,
                                     String failureDetail, String createdAt, String updatedAt) {
        this(id, workspaceId, evidenceKind, stableId, canonicalContentHash, embeddingProvider,
                embeddingModel, dimension, projectionVersion, 0L, status, vectorEncoding, vectorBlob,
                generationAttempt, generatedAt, lastAttemptAt, failureType, failureDetail, createdAt,
                updatedAt);
    }

    @Override
    public byte[] vectorBlob() {
        return vectorBlob == null ? null : Arrays.copyOf(vectorBlob, vectorBlob.length);
    }
}
