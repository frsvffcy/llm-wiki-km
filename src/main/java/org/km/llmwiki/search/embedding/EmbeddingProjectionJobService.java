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

import java.util.UUID;

/** Non-blocking production entry point for incremental and full embedding projection work. */
@Service
public class EmbeddingProjectionJobService {
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
        enqueueIncremental(workspaceId, EmbeddingEvidenceKind.WIKI, "wiki:" + knowledgePageId,
                () -> {
                    var result = projectionService.projectWiki(workspaceId, knowledgePageId);
                    int fresh = result.status() == EmbeddingProjectionOperationStatus.FRESH ? 1 : 0;
                    int failed = result.status() == EmbeddingProjectionOperationStatus.FRESH ? 0 : 1;
                    return new EmbeddingProjectionBatchResult(fresh, 1, failed);
                });
    }
    public void enqueueSourceDocument(long workspaceId, long documentId) {
        enqueueIncremental(workspaceId, EmbeddingEvidenceKind.SOURCE_CHUNK, "source-document:" + documentId,
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
        ProcessingJob job = tx.execute(status -> jobs.create(workspaceId, UUID.randomUUID().toString(),
                ProcessingJobType.EMBEDDING_REBUILD, 1));
        if (job == null) throw new IllegalStateException("Could not create embedding rebuild job");
        for (EmbeddingEvidenceKind kind : kinds(corpus)) readiness.markQueued(workspaceId, job.id(), kind, 0);
        Launch launch = new Launch(workspaceId, corpus, job);
        schedule(launch, () -> {
            int fresh = 0, failed = 0, expected = 0, ineligible = 0;
            jobs.markRunning(job.id());
            for (EmbeddingEvidenceKind kind : kinds(corpus)) {
                readiness.markRunning(workspaceId, job.id(), kind);
            }
            try {
                for (EmbeddingEvidenceKind kind : kinds(corpus)) {
                    SearchCorpus requested = kind == EmbeddingEvidenceKind.WIKI ? SearchCorpus.WIKI : SearchCorpus.SOURCE;
                    var result = projectionService.rebuildCorpus(workspaceId, requested);
                    fresh += result.fresh(); failed += result.failed(); expected += result.attempted(); ineligible += result.ineligible();
                    var metadata = projectionMetadata(workspaceId, kind);
                    readiness.markCompleted(workspaceId, job.id(), kind, result.fresh(), result.attempted(), result.failed(),
                            metadata.provider(), metadata.model(), metadata.dimension(), true);
                }
                jobs.markCompleted(job.id(), expected, fresh, failed, ineligible);
                logs.append(job.id(), null, null, "EMBEDDING_REBUILD", "SUCCEEDED", "Embedding projection rebuild completed", "{}");
            } catch (RuntimeException ex) {
                for (EmbeddingEvidenceKind kind : kinds(corpus)) readiness.markFailed(workspaceId, job.id(), kind, ex.getMessage());
                jobs.markFailed(job.id(), expected, fresh, Math.max(1, failed), ex.getMessage());
                logs.append(job.id(), null, null, "EMBEDDING_REBUILD", "FAILED", "Embedding projection rebuild failed", "{}");
            }
        });
        return new EmbeddingProjectionJobCreatedResponse(job.jobId(), ProcessingJobStatus.QUEUED.name(), workspaceId, corpus);
    }

    private void enqueueIncremental(long workspaceId, EmbeddingEvidenceKind kind, String label, java.util.function.Supplier<EmbeddingProjectionBatchResult> work) {
        ProcessingJob job = tx.execute(status -> jobs.create(workspaceId, UUID.randomUUID().toString(),
                ProcessingJobType.EMBEDDING_REBUILD, 1));
        if (job == null) return;
        readiness.markQueued(workspaceId, job.id(), kind, 1);
        schedule(new Launch(workspaceId, kind == EmbeddingEvidenceKind.WIKI ? SearchCorpus.WIKI : SearchCorpus.SOURCE, job), () -> {
            jobs.markRunning(job.id());
            readiness.markRunning(workspaceId, job.id(), kind);
            try {
                EmbeddingProjectionBatchResult result = work.get();
                var metadata = projectionService.projectionMetadata(workspaceId, kind);
                readiness.markCompleted(workspaceId, job.id(), kind, result.fresh(), result.expected(), result.failed(),
                        metadata.provider(), metadata.model(), metadata.dimension(), false);
                jobs.markCompleted(job.id(), result.expected(), result.fresh(), result.failed(), 0);
                logs.append(job.id(), null, null, "EMBEDDING_REBUILD", "SUCCEEDED", "Incremental projection completed", "{}");
            } catch (RuntimeException ex) {
                readiness.markFailed(workspaceId, job.id(), kind, ex.getMessage()); jobs.markFailed(job.id(), 1, 0, 1, ex.getMessage());
                logs.append(job.id(), null, null, "EMBEDDING_REBUILD", "FAILED", "Incremental projection failed", "{}");
            }
        });
    }
    private void schedule(Launch launch, Runnable work) {
        // Give the request's canonical transaction and its synchronous FTS
        // repair a chance to release SQLite's writer lock before the provider
        // backed projection starts. The projection remains asynchronous and
        // is still represented by the queued processing job immediately.
        Runnable task = () -> {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            work.run();
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) executor.execute(task); else
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { public void afterCommit() { executor.execute(task); } });
    }
    private static java.util.List<EmbeddingEvidenceKind> kinds(SearchCorpus corpus) {
        return corpus == SearchCorpus.WIKI ? java.util.List.of(EmbeddingEvidenceKind.WIKI)
                : corpus == SearchCorpus.SOURCE ? java.util.List.of(EmbeddingEvidenceKind.SOURCE_CHUNK)
                : java.util.List.of(EmbeddingEvidenceKind.WIKI, EmbeddingEvidenceKind.SOURCE_CHUNK);
    }
    private EmbeddingProjectionService.ProjectionMetadata projectionMetadata(long workspaceId, EmbeddingEvidenceKind kind) {
        return projectionService.projectionMetadata(workspaceId, kind);
    }
    private record Launch(long workspaceId, SearchCorpus corpus, ProcessingJob job) {}
    private record EmbeddingProjectionBatchResult(int fresh, int expected, int failed) {}
    public record EmbeddingProjectionJobCreatedResponse(String jobId, String status, long workspaceId, SearchCorpus corpus) {}
}
