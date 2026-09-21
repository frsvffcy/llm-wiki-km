package org.km.llmwiki.source;

import org.km.llmwiki.processing.ProcessingJobItemRepository;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.processing.ProcessingLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** Fails interrupted ingest jobs closed so Inbox readiness never stays PROCESSING after restart. */
@Component
public class IngestStartupReconciler {

    static final String FAILURE_DETAIL = "Interrupted by application restart";
    private static final String SAFE_METADATA = "{\"reason\":\"APPLICATION_RESTART\"}";

    private final ProcessingJobRepository jobs;
    private final ProcessingJobItemRepository items;
    private final ProcessingLogRepository logs;
    private final TransactionTemplate tx;

    public IngestStartupReconciler(ProcessingJobRepository jobs,
                                   ProcessingJobItemRepository items,
                                   ProcessingLogRepository logs,
                                   TransactionTemplate tx) {
        this.jobs = jobs;
        this.items = items;
        this.logs = logs;
        this.tx = tx;
    }

    public void reconcile() {
        tx.executeWithoutResult(status -> {
            List<Long> ids = jobs.findInterruptedIngestIds();
            items.markInterruptedIngest(ids, FAILURE_DETAIL);
            for (Long id : ids) {
                logs.append(id, null, null, "INGEST", "FAILED",
                        FAILURE_DETAIL, SAFE_METADATA);
            }
            jobs.markInterruptedIngest(ids);
        });
    }
}
