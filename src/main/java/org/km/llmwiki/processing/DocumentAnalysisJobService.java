package org.km.llmwiki.processing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.km.llmwiki.ai.DocumentAnalysisConfigurationLoader;
import org.km.llmwiki.ai.AnalysisFailureCode;
import org.km.llmwiki.ai.DocumentAnalysisPrompt;
import org.km.llmwiki.ai.DocumentAnalysisRequest;
import org.km.llmwiki.ai.DocumentAnalysisMetadata;
import org.km.llmwiki.ai.KnowledgeCandidate;
import org.km.llmwiki.ai.LlmAnalysisResult;
import org.km.llmwiki.ai.LlmAnalysisValidationException;
import org.km.llmwiki.ai.LlmClient;
import org.km.llmwiki.ai.LlmClientException;
import org.km.llmwiki.ai.PromptLoadException;
import org.km.llmwiki.ai.SourceChunkEvidence;
import org.km.llmwiki.source.DocumentAnalysisTarget;
import org.km.llmwiki.source.DocumentRepository;
import org.km.llmwiki.source.SourceChunk;
import org.km.llmwiki.source.SourceChunkRepository;
import org.km.llmwiki.wiki.KnowledgeCandidateRepository;
import org.km.llmwiki.wiki.KnowledgeCandidateValidator;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Orchestrates isolated, asynchronous LLM analysis for processed documents with source chunks. */
@Service
public class DocumentAnalysisJobService {

    private static final int MAX_RETRIES_PER_JOB_ITEM = 1;

    private final WorkspaceService workspaceService;
    private final DocumentRepository documentRepository;
    private final SourceChunkRepository sourceChunkRepository;
    private final ProcessingJobRepository jobRepository;
    private final ProcessingJobItemRepository jobItemRepository;
    private final ProcessingLogRepository logRepository;
    private final DocumentAnalysisRepository analysisRepository;
    private final KnowledgeCandidateValidator candidateValidator;
    private final KnowledgeCandidateRepository candidateRepository;
    private final DocumentAnalysisConfigurationLoader configurationLoader;
    private final LlmClient llmClient;
    private final TaskExecutor taskExecutor;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public DocumentAnalysisJobService(WorkspaceService workspaceService,
                                      DocumentRepository documentRepository,
                                      SourceChunkRepository sourceChunkRepository,
                                      ProcessingJobRepository jobRepository,
                                      ProcessingJobItemRepository jobItemRepository,
                                      ProcessingLogRepository logRepository,
                                      DocumentAnalysisRepository analysisRepository,
                                      KnowledgeCandidateValidator candidateValidator,
                                      KnowledgeCandidateRepository candidateRepository,
                                      DocumentAnalysisConfigurationLoader configurationLoader,
                                      LlmClient llmClient,
                                      @Qualifier("documentAnalysisTaskExecutor") TaskExecutor taskExecutor,
                                      TransactionTemplate transactionTemplate,
                                      ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.documentRepository = documentRepository;
        this.sourceChunkRepository = sourceChunkRepository;
        this.jobRepository = jobRepository;
        this.jobItemRepository = jobItemRepository;
        this.logRepository = logRepository;
        this.analysisRepository = analysisRepository;
        this.candidateValidator = candidateValidator;
        this.candidateRepository = candidateRepository;
        this.configurationLoader = configurationLoader;
        this.llmClient = llmClient;
        this.taskExecutor = taskExecutor;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public AnalysisJobCreatedResponse start() {
        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
        List<DocumentAnalysisTarget> targets = documentRepository.findAnalysisTargets(workspace.id());
        JobLaunch launch = transactionTemplate.execute(status -> createJob(workspace.id(), targets));
        if (launch == null) {
            throw new IllegalStateException("Could not create document analysis job");
        }
        scheduleAfterCommit(launch);
        return new AnalysisJobCreatedResponse(launch.job().jobId(), ProcessingJobStatus.QUEUED.name(),
                launch.job().totalCount());
    }

    private JobLaunch createJob(long workspaceId, List<DocumentAnalysisTarget> targets) {
        ProcessingJob job = jobRepository.create(workspaceId, UUID.randomUUID().toString(), targets.size());
        List<ProcessingJobItem> items = targets.stream()
                .map(target -> jobItemRepository.create(job.id(), target))
                .toList();
        return new JobLaunch(job, items);
    }

    private void scheduleAfterCommit(JobLaunch launch) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            taskExecutor.execute(() -> run(launch));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                taskExecutor.execute(() -> run(launch));
            }
        });
    }

    private void run(JobLaunch launch) {
        jobRepository.markRunning(launch.job().id());
        try {
            if (launch.items().isEmpty()) {
                logRepository.append(launch.job().id(), null, null, ProcessingJobItemStatus.SKIPPED,
                        "沒有符合分析條件的文件：需為 PROCESSED 且至少有一筆 Source Chunk",
                        metadata(Map.of("reason", "NO_ELIGIBLE_DOCUMENTS")));
                return;
            }
            for (ProcessingJobItem item : launch.items()) {
                analyzeOne(launch.job(), item);
            }
        } finally {
            jobRepository.markCompleted(launch.job().id());
        }
    }

    private void analyzeOne(ProcessingJob job, ProcessingJobItem item) {
        int retryCount = 0;
        while (true) {
            jobItemRepository.markRunning(item.id());
            DocumentAnalysisPrompt prompt = null;
            try {
                List<SourceChunk> chunks = sourceChunkRepository.findByDocumentId(item.document().documentId());
                if (chunks.isEmpty()) {
                    persistSkipped(job, item, "SOURCE_CHUNKS_MISSING", "文件缺少 Source Chunk，略過分析");
                    return;
                }
                DocumentAnalysisRequest request = requestFor(item.document(), chunks);
                var configuration = configurationLoader.load(request);
                prompt = configuration.prompt();
                LlmAnalysisResult result = llmClient.analyze(request.withConfiguration(configuration));
                validateEvidence(result, request);
                var candidates = candidateValidator.validate(item.document().documentId(), chunks, result.candidates());
                validateCandidateEvidence(result, candidates);
                try {
                    persistSuccess(job, item, prompt, result, candidates, retryCount);
                    return;
                } catch (RuntimeException exception) {
                    if (!persistFailureAndDecideRetry(job, item, prompt, AnalysisFailureCode.PERSISTENCE_FAILED,
                            retryCount)) {
                        return;
                    }
                    retryCount++;
                }
            } catch (PromptLoadException exception) {
                if (!persistFailureAndDecideRetry(job, item, prompt, AnalysisFailureCode.PROMPT_CONFIGURATION_FAILED,
                        retryCount)) {
                    return;
                }
                retryCount++;
            } catch (LlmClientException exception) {
                if (!persistFailureAndDecideRetry(job, item, prompt, exception.errorCode(), retryCount)) {
                    return;
                }
                retryCount++;
            } catch (RuntimeException exception) {
                if (!persistFailureAndDecideRetry(job, item, prompt, AnalysisFailureCode.UNEXPECTED_FAILURE,
                        retryCount)) {
                    return;
                }
                retryCount++;
            }
        }
    }

    private void persistSuccess(ProcessingJob job, ProcessingJobItem item, DocumentAnalysisPrompt prompt,
                                LlmAnalysisResult result, List<KnowledgeCandidate> candidates, int retryCount) {
        transactionTemplate.executeWithoutResult(status -> {
            long analysisId = analysisRepository.saveSuccess(item.id(), item.document().documentId(), prompt, result,
                    resultJson(result));
            candidateRepository.saveAll(analysisId, item.document().documentId(), candidates);
            jobItemRepository.markFinished(item.id(), ProcessingJobItemStatus.SUCCEEDED, null, null, false);
            logRepository.append(job.id(), item.id(), item.document().documentId(), ProcessingJobItemStatus.SUCCEEDED,
                    "文件分析完成", metadata(Map.of(
                            "attempt", retryCount + 1,
                            "retryCount", retryCount,
                            "sourceChunkIds", result.evidence().stream().map(evidence -> evidence.sourceChunkId()).toList(),
                            "promptIdentifier", prompt.identifier(),
                            "provider", result.metadata().provider(),
                            "model", result.metadata().model(),
                            "contractVersion", result.metadata().contractVersion())));
        });
    }

    private void persistSkipped(ProcessingJob job, ProcessingJobItem item, String errorCode, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            jobItemRepository.markFinished(item.id(), ProcessingJobItemStatus.SKIPPED, errorCode, message, false);
            logRepository.append(job.id(), item.id(), item.document().documentId(), ProcessingJobItemStatus.SKIPPED,
                    message, metadata(Map.of("reason", errorCode)));
        });
    }

    private void persistFailure(ProcessingJob job, ProcessingJobItem item, DocumentAnalysisPrompt prompt,
                                AnalysisFailureCode errorCode, int retryCount, boolean retrying) {
        transactionTemplate.executeWithoutResult(status -> {
            String message = failureMessage(errorCode);
            if (retrying) {
                jobItemRepository.markForRetry(item.id(), retryCount + 1, errorCode.name(), message);
            } else {
                analysisRepository.saveFailure(item.id(), item.document().documentId(), prompt, errorCode.name(), message);
                jobItemRepository.markFinished(item.id(), ProcessingJobItemStatus.FAILED, errorCode.name(), message,
                        errorCode.retryEligible());
            }
            logRepository.append(job.id(), item.id(), item.document().documentId(), ProcessingJobItemStatus.FAILED,
                    message, metadata(Map.of(
                            "reason", errorCode.name(),
                            "attempt", retryCount + 1,
                            "retryCount", retryCount,
                            "retryEligible", errorCode.retryEligible(),
                            "retryScheduled", retrying)));
        });
    }

    private boolean persistFailureAndDecideRetry(ProcessingJob job, ProcessingJobItem item, DocumentAnalysisPrompt prompt,
                                                  AnalysisFailureCode errorCode, int retryCount) {
        boolean retrying = errorCode.retryEligible() && retryCount < MAX_RETRIES_PER_JOB_ITEM;
        persistFailure(job, item, prompt, errorCode, retryCount, retrying);
        return retrying;
    }

    private static DocumentAnalysisRequest requestFor(DocumentAnalysisTarget document, List<SourceChunk> chunks) {
        List<SourceChunkEvidence> evidence = chunks.stream()
                .map(chunk -> new SourceChunkEvidence(chunk.id(), chunk.chunkNo(), chunk.contentHash(), chunk.content()))
                .toList();
        return new DocumentAnalysisRequest(new DocumentAnalysisMetadata(document.documentId(),
                document.originalFileName(), document.mimeType(), document.extractedTextHash()), evidence);
    }

    private static void validateEvidence(LlmAnalysisResult result, DocumentAnalysisRequest request) {
        Set<Long> requestedChunkIds = request.sourceChunkEvidence().stream()
                .map(SourceChunkEvidence::sourceChunkId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean hasUnknownEvidence = result.evidence().stream()
                .map(evidence -> evidence.sourceChunkId()).anyMatch(id -> !requestedChunkIds.contains(id));
        if (hasUnknownEvidence) {
            throw new LlmAnalysisValidationException(AnalysisFailureCode.ILLEGAL_EVIDENCE,
                    "LLM result cites a Source Chunk outside this document request");
        }
    }

    private static void validateCandidateEvidence(LlmAnalysisResult result, List<KnowledgeCandidate> candidates) {
        Set<Long> analysisEvidenceIds = result.evidence().stream().map(evidence -> evidence.sourceChunkId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (candidates.stream().anyMatch(candidate -> !analysisEvidenceIds.containsAll(candidate.evidenceSourceChunkIds()))) {
            throw new LlmAnalysisValidationException(AnalysisFailureCode.INSUFFICIENT_EVIDENCE,
                    "Knowledge candidate is not supported by the returned analysis evidence");
        }
    }

    private static String failureMessage(AnalysisFailureCode errorCode) {
        return switch (errorCode) {
            case MALFORMED_JSON -> "LLM 回應不是有效 JSON，無法進行分析";
            case CONTRACT_VALIDATION_FAILED -> "LLM 回應不符合分析契約";
            case UNKNOWN_ENUM -> "LLM 回應包含不支援的列舉值";
            case ILLEGAL_EVIDENCE -> "LLM 回應引用了不屬於本文件的證據";
            case INSUFFICIENT_EVIDENCE -> "LLM 回應沒有足夠證據支持知識 Candidate";
            case PROMPT_CONFIGURATION_FAILED -> "無法載入安全的文件分析設定";
            case PROVIDER_UNAVAILABLE -> "LLM Provider 目前無法使用";
            case PROVIDER_TIMEOUT -> "LLM Provider 回應逾時";
            case PERSISTENCE_FAILED -> "分析結果無法安全持久化";
            case UNEXPECTED_FAILURE -> "文件分析發生未預期錯誤";
        };
    }

    private String resultJson(LlmAnalysisResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not save validated document analysis result", exception);
        }
    }

    private String metadata(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not save analysis diagnostic metadata", exception);
        }
    }

    private record JobLaunch(ProcessingJob job, List<ProcessingJobItem> items) {
        private JobLaunch {
            Objects.requireNonNull(job, "job must not be null");
            items = List.copyOf(items);
        }
    }
}
