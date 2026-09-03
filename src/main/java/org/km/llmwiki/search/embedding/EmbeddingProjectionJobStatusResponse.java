package org.km.llmwiki.search.embedding;

import org.km.llmwiki.processing.ProcessingJobStatus;
import org.km.llmwiki.processing.ProcessingJobType;
import org.km.llmwiki.search.SearchCorpus;

/** Safe public representation of one embedding rebuild operation. */
public record EmbeddingProjectionJobStatusResponse(
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
