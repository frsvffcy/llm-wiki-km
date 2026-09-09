package org.km.llmwiki.search;

import org.km.llmwiki.processing.ProcessingJobDetails;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.processing.ProcessingJobStatus;
import org.km.llmwiki.processing.ProcessingOperationNotFoundException;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

/** Resolves active-workspace-scoped FTS lifecycle data from the processing-job ledger. */
@Service
public class FtsRebuildJobQueryService {

    private final WorkspaceService workspaceService;
    private final ProcessingJobRepository jobs;

    public FtsRebuildJobQueryService(WorkspaceService workspaceService, ProcessingJobRepository jobs) {
        this.workspaceService = workspaceService;
        this.jobs = jobs;
    }

    public FtsRebuildJobStatusResponse find(String jobId) {
        long workspaceId = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new).id();
        ProcessingJobDetails job = jobs.findFtsRebuild(workspaceId, jobId)
                .orElseThrow(ProcessingOperationNotFoundException::new);
        SearchCorpus corpus = FtsRebuildOperationMetadataCodec.decode(job.operationMetadataJson())
                .orElse(null);
        FailureDiagnostic failure = failure(job);
        return new FtsRebuildJobStatusResponse(
                job.jobId(), job.jobType(), corpus, job.status(), job.totalCount(), job.processedCount(),
                job.successCount(), job.failedCount(), job.skippedCount(), job.createdAt(),
                job.startedAt(), job.finishedAt(), failure.code(), failure.summary());
    }

    private static FailureDiagnostic failure(ProcessingJobDetails job) {
        if (job.status() == ProcessingJobStatus.COMPLETED && job.failedCount() > 0) {
            return new FailureDiagnostic("PARTIAL_FAILURE", "FTS rebuild completed with failed items");
        }
        if (job.status() == ProcessingJobStatus.FAILED) {
            return new FailureDiagnostic("REBUILD_FAILED", "FTS rebuild failed");
        }
        return FailureDiagnostic.NONE;
    }

    private record FailureDiagnostic(String code, String summary) {
        private static final FailureDiagnostic NONE = new FailureDiagnostic(null, null);
    }
}
