package org.km.llmwiki.search.embedding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.processing.ProcessingJob;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.processing.ProcessingJobStatus;
import org.km.llmwiki.processing.ProcessingJobType;
import org.km.llmwiki.processing.ProcessingLogRepository;
import org.km.llmwiki.search.SearchCorpus;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class EmbeddingProjectionJobServiceTest {

    private static final long WORKSPACE_ID = 7L;
    private static final ProcessingJob JOB = new ProcessingJob(17L, "embedding-job-17", 1);

    private WorkspaceService workspaceService;
    private EmbeddingProjectionService projectionService;
    private EmbeddingProjectionReadinessRepository readiness;
    private ProcessingJobRepository jobs;
    private ProcessingLogRepository logs;
    private TransactionTemplate transactionTemplate;
    private List<Runnable> submitted;
    private EmbeddingProjectionJobService service;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        projectionService = mock(EmbeddingProjectionService.class);
        readiness = mock(EmbeddingProjectionReadinessRepository.class);
        jobs = mock(ProcessingJobRepository.class);
        logs = mock(ProcessingLogRepository.class);
        transactionTemplate = mock(TransactionTemplate.class);
        submitted = new ArrayList<>();

        when(jobs.create(eq(WORKSPACE_ID), anyString(), eq(ProcessingJobType.EMBEDDING_REBUILD), eq(1)))
                .thenReturn(JOB);
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        Thread.interrupted();
    }

    @Test
    void dispatchesOnlyAfterCommittedTransactionAndReachesTerminalSuccess() {
        service = service(command -> submitted.add(command));
        when(projectionService.projectWiki(WORKSPACE_ID, 101L)).thenReturn(freshWikiResult());
        when(projectionService.projectionMetadata(WORKSPACE_ID, EmbeddingEvidenceKind.WIKI))
                .thenReturn(new EmbeddingProjectionService.ProjectionMetadata("test-provider", "test-model", 2));
        when(projectionService.projectionCounts(WORKSPACE_ID, EmbeddingEvidenceKind.WIKI))
                .thenReturn(new EmbeddingProjectionService.ProjectionCounts(1, 1, 0));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        service.enqueueWiki(WORKSPACE_ID, 101L);

        verify(readiness).markQueued(WORKSPACE_ID, JOB.id(), EmbeddingEvidenceKind.WIKI, 1);
        assertThat(submitted).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().getFirst()
                .afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(submitted).hasSize(1);
        submitted.getFirst().run();

        verify(jobs).markRunning(JOB.id());
        verify(readiness).markRunning(WORKSPACE_ID, JOB.id(), EmbeddingEvidenceKind.WIKI);
        verify(readiness).markCompleted(WORKSPACE_ID, JOB.id(), EmbeddingEvidenceKind.WIKI,
                1, 1, 0, "test-provider", "test-model", 2, false);
        verify(jobs).markCompleted(JOB.id(), 1, 1, 1, 0, 0);
        verify(logs).append(JOB.id(), null, null, "EMBEDDING_REBUILD", "SUCCEEDED",
                "Incremental projection completed", "{}");
    }

    @Test
    void submissionRejectionImmediatelyMarksJobAndReadinessFailed() {
        service = service(command -> {
            throw new RejectedExecutionException("embedding executor is shut down");
        });

        service.enqueueWiki(WORKSPACE_ID, 101L);

        verify(readiness).markFailed(eq(WORKSPACE_ID), eq(JOB.id()), eq(EmbeddingEvidenceKind.WIKI),
                contains("DISPATCH_REJECTED"));
        verify(jobs).markFailed(eq(JOB.id()), eq(1), eq(0), eq(1), contains("DISPATCH_REJECTED"));
        verify(logs).append(eq(JOB.id()), eq(null), eq(null), eq("EMBEDDING_REBUILD"), eq("FAILED"),
                eq("Embedding projection rebuild failed"), contains("DISPATCH_REJECTED"));
        verify(jobs, never()).markRunning(JOB.id());
        verify(readiness, never()).markRunning(any(Long.class), any(Long.class), any(EmbeddingEvidenceKind.class));
    }

    @Test
    void preRunInterruptionPreservesFlagAndMarksTerminalFailure() {
        service = service(command -> {
            Thread.currentThread().interrupt();
            command.run();
        });

        service.enqueueWiki(WORKSPACE_ID, 101L);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(readiness).markFailed(eq(WORKSPACE_ID), eq(JOB.id()), eq(EmbeddingEvidenceKind.WIKI),
                contains("PRE_RUN_INTERRUPTED"));
        verify(jobs).markFailed(eq(JOB.id()), eq(1), eq(0), eq(1), contains("PRE_RUN_INTERRUPTED"));
        verify(logs).append(eq(JOB.id()), eq(null), eq(null), eq("EMBEDDING_REBUILD"), eq("FAILED"),
                eq("Embedding projection rebuild failed"), contains("PRE_RUN_INTERRUPTED"));
        verify(jobs, never()).markRunning(JOB.id());
        verify(projectionService, never()).projectWiki(any(Long.class), any(Long.class));
    }

    @Test
    void sourceCleanupIsSkippedAccountingAndCannotBecomeFailure() {
        service = service(command -> submitted.add(command));
        when(projectionService.removeOrphanedSourceProjections(WORKSPACE_ID)).thenReturn(3);
        when(projectionService.sourceChunks(WORKSPACE_ID, 202L)).thenReturn(List.of(301L));
        when(projectionService.projectSourceChunk(WORKSPACE_ID, 301L)).thenReturn(freshSourceResult());
        when(projectionService.projectionMetadata(WORKSPACE_ID, EmbeddingEvidenceKind.SOURCE_CHUNK))
                .thenReturn(new EmbeddingProjectionService.ProjectionMetadata("provider", "model", 2));
        when(projectionService.projectionCounts(WORKSPACE_ID, EmbeddingEvidenceKind.SOURCE_CHUNK))
                .thenReturn(new EmbeddingProjectionService.ProjectionCounts(1, 1, 0));

        service.enqueueSourceDocument(WORKSPACE_ID, 202L);
        submitted.getFirst().run();

        verify(readiness).markCompleted(WORKSPACE_ID, JOB.id(), EmbeddingEvidenceKind.SOURCE_CHUNK,
                1, 1, 0, "provider", "model", 2, false);
        verify(jobs).markCompleted(JOB.id(), 4, 4, 1, 0, 3);
        verify(logs).append(JOB.id(), null, null, "EMBEDDING_REBUILD", "SUCCEEDED",
                "Incremental projection completed", "{}");
    }

    @Test
    void schedulingFailureLeavesPersistedStaleRepairState() {
        service = service(command -> submitted.add(command));
        doThrow(new IllegalStateException("SELECT secret FROM /native/vec0.dylib"))
                .when(transactionTemplate).execute(any());

        assertThatThrownBy(() -> service.enqueueWiki(WORKSPACE_ID, 101L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SELECT secret FROM /native/vec0.dylib");

        verify(readiness).markSchedulingStale(WORKSPACE_ID, EmbeddingEvidenceKind.WIKI,
                "Canonical content changed; embedding projection repair is pending");
        verify(readiness).markSchedulingStale(eq(WORKSPACE_ID), eq(EmbeddingEvidenceKind.WIKI),
                contains("Embedding projection enqueue failed"));
        verify(readiness, never()).markSchedulingStale(eq(WORKSPACE_ID), eq(EmbeddingEvidenceKind.WIKI),
                contains("SELECT secret"));
        assertThat(submitted).isEmpty();
        verify(jobs, never()).markRunning(any(Long.class));
    }

    @Test
    void nullDurableEnqueueResultIsASchedulingFailure() {
        service = service(command -> submitted.add(command));
        doAnswer(invocation -> null).when(transactionTemplate).execute(any());

        assertThatThrownBy(() -> service.enqueueWiki(WORKSPACE_ID, 101L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not create embedding incremental job");

        verify(readiness).markSchedulingStale(WORKSPACE_ID, EmbeddingEvidenceKind.WIKI,
                "Canonical content changed; embedding projection repair is pending");
        verify(readiness).markSchedulingStale(eq(WORKSPACE_ID), eq(EmbeddingEvidenceKind.WIKI),
                contains("Embedding projection enqueue failed"));
        assertThat(submitted).isEmpty();
    }

    private EmbeddingProjectionJobService service(TaskExecutor executor) {
        return new EmbeddingProjectionJobService(workspaceService, projectionService, readiness, jobs, logs,
                executor, transactionTemplate);
    }

    private static EmbeddingProjectionResult freshWikiResult() {
        return new EmbeddingProjectionResult(EmbeddingProjectionOperationStatus.FRESH, WORKSPACE_ID,
                EmbeddingEvidenceKind.WIKI, "wiki-101", "content-hash", null, null);
    }

    private static EmbeddingProjectionResult freshSourceResult() {
        return new EmbeddingProjectionResult(EmbeddingProjectionOperationStatus.FRESH, WORKSPACE_ID,
                EmbeddingEvidenceKind.SOURCE_CHUNK, "301", "content-hash", null, null);
    }
}
