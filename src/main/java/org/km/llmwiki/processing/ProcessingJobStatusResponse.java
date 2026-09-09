package org.km.llmwiki.processing;

/** Safe, read-only public projection shared by processing operation status endpoints. */
public record ProcessingJobStatusResponse(
        String jobId,
        ProcessingJobType jobType,
        ProcessingJobStatus status,
        int totalCount,
        int processedCount,
        int successCount,
        int failedCount,
        int skippedCount,
        String createdAt,
        String startedAt,
        String completedAt,
        String failureCode,
        String failureSummary) {
}
