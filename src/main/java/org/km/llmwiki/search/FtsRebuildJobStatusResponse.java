package org.km.llmwiki.search;

import org.km.llmwiki.processing.ProcessingJobStatus;
import org.km.llmwiki.processing.ProcessingJobType;

/** Safe, read-only public representation of one FTS rebuild operation. */
public record FtsRebuildJobStatusResponse(
        String jobId,
        ProcessingJobType jobType,
        SearchCorpus corpus,
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
