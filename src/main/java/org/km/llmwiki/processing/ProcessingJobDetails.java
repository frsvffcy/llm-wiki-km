package org.km.llmwiki.processing;

/** Read-only lifecycle data for an operator-facing processing job query. */
public record ProcessingJobDetails(
        long id,
        String jobId,
        ProcessingJobType jobType,
        ProcessingJobStatus status,
        int totalCount,
        int processedCount,
        int successCount,
        int failedCount,
        int skippedCount,
        String startedAt,
        String finishedAt,
        String createdAt,
        String updatedAt,
        String operationMetadataJson) {
}
