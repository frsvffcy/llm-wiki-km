package org.km.llmwiki.processing;

import org.km.llmwiki.ai.AnalysisFailureCode;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Resolves active-workspace-scoped analysis lifecycle data without exposing job internals. */
@Service
public class AnalysisJobQueryService {

    private final WorkspaceService workspaceService;
    private final ProcessingJobRepository jobs;

    public AnalysisJobQueryService(WorkspaceService workspaceService, ProcessingJobRepository jobs) {
        this.workspaceService = workspaceService;
        this.jobs = jobs;
    }

    public ProcessingJobStatusResponse find(String jobId) {
        long workspaceId = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new).id();
        ProcessingJobDetails job = jobs.findAnalysis(workspaceId, jobId)
                .orElseThrow(ProcessingOperationNotFoundException::new);
        FailureDiagnostic failure = failure(job, jobs.findLatestAnalysisFailureCode(job.id()));
        return new ProcessingJobStatusResponse(
                job.jobId(), job.jobType(), job.status(), job.totalCount(), job.processedCount(),
                job.successCount(), job.failedCount(), job.skippedCount(), job.createdAt(),
                job.startedAt(), job.finishedAt(), failure.code(), failure.summary());
    }

    private static FailureDiagnostic failure(ProcessingJobDetails job, Optional<String> failureCode) {
        if (job.status() == ProcessingJobStatus.COMPLETED && job.failedCount() > 0) {
            return new FailureDiagnostic("PARTIAL_FAILURE",
                    "Document analysis completed with failed items");
        }
        if (job.status() != ProcessingJobStatus.FAILED) {
            return FailureDiagnostic.NONE;
        }
        String code = failureCode.flatMap(AnalysisJobQueryService::allowListedFailureCode)
                .orElse("PROCESSING_FAILED");
        return new FailureDiagnostic(code, summaryFor(code));
    }

    private static Optional<String> allowListedFailureCode(String code) {
        try {
            return Optional.of(AnalysisFailureCode.valueOf(code).name());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return Optional.empty();
        }
    }

    private static String summaryFor(String code) {
        return switch (code) {
            case "PROVIDER_UNAVAILABLE" -> "Document analysis provider unavailable";
            case "PROVIDER_TIMEOUT" -> "Document analysis provider timed out";
            case "PROMPT_CONFIGURATION_FAILED" -> "Document analysis prompt configuration failed";
            case "PERSISTENCE_FAILED" -> "Document analysis persistence failed";
            case "MALFORMED_JSON", "CONTRACT_VALIDATION_FAILED", "UNKNOWN_ENUM",
                    "ILLEGAL_EVIDENCE", "INSUFFICIENT_EVIDENCE" ->
                    "Document analysis failed validation";
            case "UNEXPECTED_FAILURE" -> "Document analysis failed";
            default -> "Document analysis failed";
        };
    }

    private record FailureDiagnostic(String code, String summary) {
        private static final FailureDiagnostic NONE = new FailureDiagnostic(null, null);
    }
}
