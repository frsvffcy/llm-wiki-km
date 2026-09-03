package org.km.llmwiki.search.embedding;

public record EmbeddingProjectionReadiness(long workspaceId, EmbeddingEvidenceKind corpus,
                                           EmbeddingProjectionReadinessStatus status,
                                           Long processingJobId, int indexedCount, int expectedCount,
                                           int failedCount, String provider, String model,
                                           Integer dimension, String projectionVersion,
                                           String failureDetail, String startedAt, String completedAt,
                                           String updatedAt, long targetGeneration, long appliedGeneration,
                                           String projectionSnapshotToken) {
    public EmbeddingProjectionReadiness(long workspaceId, EmbeddingEvidenceKind corpus,
                                        EmbeddingProjectionReadinessStatus status,
                                        Long processingJobId, int indexedCount, int expectedCount,
                                        int failedCount, String provider, String model,
                                        Integer dimension, String projectionVersion,
                                        String failureDetail, String startedAt, String completedAt,
                                        String updatedAt) {
        this(workspaceId, corpus, status, processingJobId, indexedCount, expectedCount, failedCount,
                provider, model, dimension, projectionVersion, failureDetail, startedAt, completedAt,
                updatedAt, 0L, 0L, null);
    }
}
