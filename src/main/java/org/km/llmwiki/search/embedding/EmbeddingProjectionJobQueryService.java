package org.km.llmwiki.search.embedding;

import org.km.llmwiki.processing.ProcessingJobDetails;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.processing.ProcessingJobStatus;
import org.km.llmwiki.processing.ProcessingLogRepository;
import org.km.llmwiki.search.SearchCorpus;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves workspace-scoped embedding rebuild lifecycle data without exposing job internals. */
@Service
public class EmbeddingProjectionJobQueryService {
    private static final Pattern FAILURE_TYPE = Pattern.compile(
            "\\\"failureType\\\"\\s*:\\s*\\\"([A-Z_]+)\\\"");

    private final WorkspaceService workspaceService;
    private final ProcessingJobRepository jobs;
    private final ProcessingLogRepository logs;
    private final EmbeddingProjectionReadinessRepository readiness;

    public EmbeddingProjectionJobQueryService(WorkspaceService workspaceService,
                                              ProcessingJobRepository jobs,
                                              ProcessingLogRepository logs,
                                              EmbeddingProjectionReadinessRepository readiness) {
        this.workspaceService = workspaceService;
        this.jobs = jobs;
        this.logs = logs;
        this.readiness = readiness;
    }

    public EmbeddingProjectionJobStatusResponse find(String jobId) {
        long workspaceId = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new).id();
        ProcessingJobDetails job = jobs.findEmbeddingRebuild(workspaceId, jobId)
                .orElseThrow(ProcessingJobNotFoundException::new);
        SearchCorpus corpus = corpus(readiness.findCorporaForJob(workspaceId, job.id()));
        FailureDiagnostic failure = failure(job, logs.findLatestEmbeddingFailureMetadata(job.id()));
        return new EmbeddingProjectionJobStatusResponse(
                job.jobId(), job.jobType(), corpus, job.status(), job.totalCount(), job.processedCount(),
                job.successCount(), job.failedCount(), job.skippedCount(), job.createdAt(), job.startedAt(),
                job.finishedAt(), failure.code(), failure.summary());
    }

    private static SearchCorpus corpus(List<EmbeddingEvidenceKind> kinds) {
        boolean wiki = kinds.contains(EmbeddingEvidenceKind.WIKI);
        boolean source = kinds.contains(EmbeddingEvidenceKind.SOURCE_CHUNK);
        if (wiki && source) return SearchCorpus.ALL;
        if (wiki) return SearchCorpus.WIKI;
        if (source) return SearchCorpus.SOURCE;
        return null;
    }

    private static FailureDiagnostic failure(ProcessingJobDetails job, Optional<String> metadata) {
        if (job.status() == ProcessingJobStatus.COMPLETED && job.failedCount() > 0) {
            return new FailureDiagnostic("PARTIAL_FAILURE",
                    "Embedding rebuild completed with failed items");
        }
        if (job.status() != ProcessingJobStatus.FAILED) {
            return FailureDiagnostic.NONE;
        }
        String code = metadata.flatMap(EmbeddingProjectionJobQueryService::allowListedFailureCode)
                .orElse("PROCESSING_FAILED");
        return new FailureDiagnostic(code, summaryFor(code));
    }

    private static Optional<String> allowListedFailureCode(String metadata) {
        if (metadata == null) return Optional.empty();
        Matcher matcher = FAILURE_TYPE.matcher(metadata);
        if (!matcher.find()) return Optional.empty();
        return switch (matcher.group(1)) {
            case "DISPATCH_REJECTED", "PRE_RUN_INTERRUPTED", "REBUILD_FAILED" ->
                    Optional.of(matcher.group(1));
            default -> Optional.empty();
        };
    }

    private static String summaryFor(String code) {
        return switch (code) {
            case "DISPATCH_REJECTED" -> "Embedding rebuild could not be queued";
            case "PRE_RUN_INTERRUPTED" -> "Embedding rebuild worker was interrupted before execution";
            case "REBUILD_FAILED", "PROCESSING_FAILED" -> "Embedding rebuild failed";
            default -> "Embedding rebuild failed";
        };
    }

    private record FailureDiagnostic(String code, String summary) {
        private static final FailureDiagnostic NONE = new FailureDiagnostic(null, null);
    }
}
