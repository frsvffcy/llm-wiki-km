package org.km.llmwiki.search;

import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.processing.ProcessingLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Fail-closed startup boundary for rebuild work that belonged to a previous local process.
 *
 * <p>The reconciler is invoked exactly while the single rebuild worker bean is being created.
 * It never changes FTS projections or canonical knowledge data.
 */
@Component
public class FtsRebuildStartupReconciler {

    static final String FAILURE_DETAIL = "Interrupted by application restart";
    private static final String SAFE_METADATA = "{\"reason\":\"APPLICATION_RESTART\"}";

    private final FtsRebuildStateRepository rebuildStateRepository;
    private final ProcessingJobRepository jobRepository;
    private final ProcessingLogRepository logRepository;
    private final TransactionTemplate transactionTemplate;

    public FtsRebuildStartupReconciler(FtsRebuildStateRepository rebuildStateRepository,
                                       ProcessingJobRepository jobRepository,
                                       ProcessingLogRepository logRepository,
                                       TransactionTemplate transactionTemplate) {
        this.rebuildStateRepository = rebuildStateRepository;
        this.jobRepository = jobRepository;
        this.logRepository = logRepository;
        this.transactionTemplate = transactionTemplate;
    }

    public RecoveryResult reconcile() {
        RecoveryResult result = transactionTemplate.execute(status -> reconcileInTransaction());
        return result == null ? new RecoveryResult(0, 0) : result;
    }

    private RecoveryResult reconcileInTransaction() {
        List<Long> linkedJobIds = rebuildStateRepository.findInProgressProcessingJobIds();
        List<Long> jobIds = jobRepository.findInterruptedIds(linkedJobIds);
        int recoveredStates = rebuildStateRepository.markInterrupted(FAILURE_DETAIL);
        int recoveredJobs = jobRepository.markInterrupted(jobIds);
        for (Long jobId : jobIds) {
            logRepository.append(jobId, null, null, "FTS_REBUILD", "FAILED",
                    FAILURE_DETAIL, SAFE_METADATA);
        }
        return new RecoveryResult(recoveredStates, recoveredJobs);
    }

    public record RecoveryResult(int rebuildStates, int processingJobs) {
    }
}
