package org.km.llmwiki.search.embedding;

import org.km.llmwiki.processing.*;
import org.km.llmwiki.search.SearchCorpus;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.UUID;

/** Non-blocking production entry point for incremental and full embedding projection work. */
@Service
public class EmbeddingProjectionJobService {
    private static final String FAILURE_DISPATCH_REJECTED = "DISPATCH_REJECTED";
    private static final String FAILURE_PRE_RUN_INTERRUPTED = "PRE_RUN_INTERRUPTED";
    private static final String FAILURE_REBUILD = "REBUILD_FAILED";

    private final WorkspaceService workspaceService;
    private final EmbeddingProjectionService projectionService;
    private final EmbeddingProjectionReadinessRepository readiness;
    private final ProcessingJobRepository jobs;
    private final ProcessingLogRepository logs;
    private final TaskExecutor executor;
    private final TransactionTemplate tx;

    public EmbeddingProjectionJobService(WorkspaceService workspaceService,
                                         EmbeddingProjectionService projectionService,
                                         EmbeddingProjectionReadinessRepository readiness,
                                         ProcessingJobRepository jobs, ProcessingLogRepository logs,
                                         @Qualifier("embeddingProjectionTaskExecutor") TaskExecutor executor,
                                         TransactionTemplate tx) {
        this.workspaceService = workspaceService; this.projectionService = projectionService;
        this.readiness = readiness; this.jobs = jobs; this.logs = logs; this.executor = executor; this.tx = tx;
    }

    public void enqueueWiki(long workspaceId, long knowledgePageId) {
        enqueueIncremental(workspaceId, EmbeddingEvidenceKind.WIKI,
                () -> {
                    var result = projectionService.projectWiki(workspaceId, knowledgePageId);
                    int fresh = result.status() == EmbeddingProjectionOperationStatus.FRESH ? 1 : 0;
                    int failed = result.status() == EmbeddingProjectionOperationStatus.FRESH ? 0 : 1;
                    return new EmbeddingProjectionBatchResult(fresh, 1, failed);
                });
    }
    public void enqueueSourceDocument(long workspaceId, long documentId) {
        enqueueIncremental(workspaceId, EmbeddingEvidenceKind.SOURCE_CHUNK,
                () -> {
                    int removed = projectionService.removeOrphanedSourceProjections(workspaceId);
                    var authority = projectionService.sourceChunks(workspaceId, documentId);
                    int failed = 0, fresh = 0;
                    for (long chunkId : authority) {
                        var result = projectionService.projectSourceChunk(workspaceId, chunkId);
                        if (result.status() == EmbeddingProjectionOperationStatus.FRESH) fresh++; else failed++;
                    }
                    return new EmbeddingProjectionBatchResult(fresh, authority.size(), failed + removed);
                });
    }
    public EmbeddingProjectionJobCreatedResponse startRebuild(SearchCorpus corpus) {
        long workspaceId = workspaceService.findActiveWithoutValidation().orElseThrow(NoActiveWorkspaceException::new).id();
        List<EmbeddingEvidenceKind> kinds = kinds(corpus);
        Launch launch = tx.execute(status -> {
            ProcessingJob job = jobs.create(workspaceId, UUID.randomUUID().toString(),
                    ProcessingJobType.EMBEDDING_REBUILD, 1);
            for (EmbeddingEvidenceKind kind : kinds) readiness.markQueued(workspaceId, job.id(), kind, 0);
            return new Launch(workspaceId, corpus, job);
        });
        if (launch == null) throw new IllegalStateException("Could not create embedding rebuild job");
        schedule(launch, () -> runRebuild(launch));
        return new EmbeddingProjectionJobCreatedResponse(launch.job().jobId(), ProcessingJobStatus.QUEUED.name(), workspaceId, corpus);
    }

    private void enqueueIncremental(long workspaceId, EmbeddingEvidenceKind kind,
                                    java.util.function.Supplier<EmbeddingProjectionBatchResult> work) {
        Launch launch = tx.execute(status -> {
            ProcessingJob job = jobs.create(workspaceId, UUID.randomUUID().toString(),
                    ProcessingJobType.EMBEDDING_REBUILD, 1);
            readiness.markQueued(workspaceId, job.id(), kind, 1);
            return new Launch(workspaceId,
                    kind == EmbeddingEvidenceKind.WIKI ? SearchCorpus.WIKI : SearchCorpus.SOURCE, job);
        });
        if (launch == null) return;
        schedule(launch, () -> runIncremental(launch, kind, work));
    }

    private void runRebuild(Launch launch) {
        int fresh = 0, failed = 0, expected = 0, ineligible = 0;
        List<EmbeddingEvidenceKind> kinds = kinds(launch.corpus());
        try {
            tx.executeWithoutResult(status -> {
                jobs.markRunning(launch.job().id());
                for (EmbeddingEvidenceKind kind : kinds) readiness.markRunning(launch.workspaceId(), launch.job().id(), kind);
            });

            List<RebuildOutcome> outcomes = new ArrayList<>();
            for (EmbeddingEvidenceKind kind : kinds) {
                SearchCorpus requested = kind == EmbeddingEvidenceKind.WIKI ? SearchCorpus.WIKI : SearchCorpus.SOURCE;
                var result = projectionService.rebuildCorpus(launch.workspaceId(), requested);
                fresh += result.fresh();
                failed += result.failed();
                expected += result.attempted();
                ineligible += result.ineligible();
                outcomes.add(new RebuildOutcome(kind, result, projectionMetadata(launch.workspaceId(), kind)));
            }
            int completedFresh = fresh;
            int completedFailed = failed;
            int completedExpected = expected;
            int completedIneligible = ineligible;
            tx.executeWithoutResult(status -> {
                for (RebuildOutcome outcome : outcomes) {
                    var result = outcome.result();
                    var metadata = outcome.metadata();
                    readiness.markCompleted(launch.workspaceId(), launch.job().id(), outcome.kind(),
                            result.fresh(), result.attempted(), result.failed(), metadata.provider(),
                            metadata.model(), metadata.dimension(), true);
                }
                jobs.markCompleted(launch.job().id(), completedExpected, completedFresh,
                        completedFailed, completedIneligible);
                logs.append(launch.job().id(), null, null, "EMBEDDING_REBUILD", "SUCCEEDED",
                        "Embedding projection rebuild completed", "{}");
            });
        } catch (RuntimeException failure) {
            recordFailure(launch, kinds, FAILURE_REBUILD, failure, expected, fresh, Math.max(1, failed));
        }
    }

    private void runIncremental(Launch launch, EmbeddingEvidenceKind kind,
                                java.util.function.Supplier<EmbeddingProjectionBatchResult> work) {
        try {
            tx.executeWithoutResult(status -> {
                jobs.markRunning(launch.job().id());
                readiness.markRunning(launch.workspaceId(), launch.job().id(), kind);
            });
            EmbeddingProjectionBatchResult result = work.get();
            var metadata = projectionService.projectionMetadata(launch.workspaceId(), kind);
            tx.executeWithoutResult(status -> {
                readiness.markCompleted(launch.workspaceId(), launch.job().id(), kind, result.fresh(), result.expected(),
                        result.failed(), metadata.provider(), metadata.model(), metadata.dimension(), false);
                jobs.markCompleted(launch.job().id(), result.expected(), result.fresh(), result.failed(), 0);
                logs.append(launch.job().id(), null, null, "EMBEDDING_REBUILD", "SUCCEEDED",
                        "Incremental projection completed", "{}");
            });
        } catch (RuntimeException failure) {
            recordFailure(launch, List.of(kind), FAILURE_REBUILD, failure);
        }
    }

    private void schedule(Launch launch, Runnable work) {
        Runnable task = () -> {
            if (Thread.currentThread().isInterrupted()) {
                recordFailure(launch, kinds(launch.corpus()), FAILURE_PRE_RUN_INTERRUPTED,
                        new IllegalStateException("Embedding projection worker was interrupted before execution"));
                return;
            }
            work.run();
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            dispatch(launch, task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) dispatch(launch, task);
            }
        });
    }

    private void dispatch(Launch launch, Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rejected) {
            recordFailure(launch, kinds(launch.corpus()), FAILURE_DISPATCH_REJECTED, rejected);
        }
    }

    private void recordFailure(Launch launch, List<EmbeddingEvidenceKind> kinds,
                               String reason, RuntimeException failure) {
        recordFailure(launch, kinds, reason, failure, 1, 0, 1);
    }

    private void recordFailure(Launch launch, List<EmbeddingEvidenceKind> kinds,
                               String reason, RuntimeException failure,
                               int processed, int succeeded, int failed) {
        String detail = reason + ": " + safeMessage(failure);
        tx.executeWithoutResult(status -> {
            for (EmbeddingEvidenceKind kind : kinds) {
                readiness.markFailed(launch.workspaceId(), launch.job().id(), kind, detail);
            }
            jobs.markFailed(launch.job().id(), processed, succeeded, Math.max(1, failed), detail);
            logs.append(launch.job().id(), null, null, "EMBEDDING_REBUILD", "FAILED",
                    "Embedding projection rebuild failed", "{\"failureType\":\"" + reason + "\"}");
        });
    }
    private static List<EmbeddingEvidenceKind> kinds(SearchCorpus corpus) {
        return corpus == SearchCorpus.WIKI ? List.of(EmbeddingEvidenceKind.WIKI)
                : corpus == SearchCorpus.SOURCE ? List.of(EmbeddingEvidenceKind.SOURCE_CHUNK)
                : List.of(EmbeddingEvidenceKind.WIKI, EmbeddingEvidenceKind.SOURCE_CHUNK);
    }
    private EmbeddingProjectionService.ProjectionMetadata projectionMetadata(long workspaceId, EmbeddingEvidenceKind kind) {
        return projectionService.projectionMetadata(workspaceId, kind);
    }
    private record Launch(long workspaceId, SearchCorpus corpus, ProcessingJob job) {}
    private record RebuildOutcome(EmbeddingEvidenceKind kind, EmbeddingProjectionRebuildResult result,
                                  EmbeddingProjectionService.ProjectionMetadata metadata) {}
    private record EmbeddingProjectionBatchResult(int fresh, int expected, int failed) {}
    public record EmbeddingProjectionJobCreatedResponse(String jobId, String status, long workspaceId, SearchCorpus corpus) {}

    private static String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
