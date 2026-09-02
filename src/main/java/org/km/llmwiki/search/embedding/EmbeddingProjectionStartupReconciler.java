package org.km.llmwiki.search.embedding;

import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.processing.ProcessingLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** Marks interrupted embedding jobs as failed so readiness never survives a restart as ready. */
@Component
public class EmbeddingProjectionStartupReconciler {
    private final EmbeddingProjectionReadinessRepository readiness;
    private final ProcessingJobRepository jobs;
    private final ProcessingLogRepository logs;
    private final TransactionTemplate tx;
    public EmbeddingProjectionStartupReconciler(EmbeddingProjectionReadinessRepository readiness,
                                                ProcessingJobRepository jobs, ProcessingLogRepository logs,
                                                TransactionTemplate tx) {
        this.readiness = readiness; this.jobs = jobs; this.logs = logs; this.tx = tx;
    }
    public void reconcile() {
        tx.executeWithoutResult(status -> {
            List<Long> ids = jobs.findInterruptedEmbeddingIds();
            readiness.markInterrupted(ids, "Interrupted by application restart");
            for (Long id : ids) logs.append(id, null, null, "EMBEDDING_REBUILD", "FAILED",
                    "Interrupted by application restart", "{\"reason\":\"APPLICATION_RESTART\"}");
            jobs.markInterruptedEmbedding(ids);
        });
    }
}
