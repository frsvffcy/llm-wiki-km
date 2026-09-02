package org.km.llmwiki.search.embedding;

import java.util.Arrays;

/** Relational projection row; it intentionally contains no provider response or credential. */
public record StoredEmbeddingProjection(long id, long workspaceId, EmbeddingEvidenceKind evidenceKind,
                                        String stableId, String canonicalContentHash,
                                        String embeddingProvider, String embeddingModel, Integer dimension,
                                        String projectionVersion, EmbeddingProjectionStatus status,
                                        String vectorEncoding, byte[] vectorBlob, int generationAttempt,
                                        String generatedAt, String lastAttemptAt, String failureType,
                                        String failureDetail, String createdAt, String updatedAt) {

    public StoredEmbeddingProjection {
        vectorBlob = vectorBlob == null ? null : Arrays.copyOf(vectorBlob, vectorBlob.length);
    }

    @Override
    public byte[] vectorBlob() {
        return vectorBlob == null ? null : Arrays.copyOf(vectorBlob, vectorBlob.length);
    }
}
