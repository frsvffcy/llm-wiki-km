package org.km.llmwiki.source;

import org.km.llmwiki.processing.ProcessingJob;
import org.km.llmwiki.processing.ProcessingJobItemRepository;
import org.km.llmwiki.processing.ProcessingJobItemStatus;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.processing.ProcessingJobType;
import org.km.llmwiki.processing.ProcessingLogRepository;
import org.km.llmwiki.search.SourceSearchIndexSyncRepository;
import org.km.llmwiki.search.SourceSearchIndexSyncStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Queues bounded asynchronous extraction + Source FTS synchronization for uploaded documents. */
@Service
public class IngestProcessingService {

    private static final String STEP = "INGEST";

    private final ProcessingJobRepository jobs;
    private final ProcessingJobItemRepository items;
    private final ProcessingLogRepository logs;
    private final ExtractedContentService extraction;
    private final SourceSearchIndexSyncRepository syncRepository;
    private final ThreadPoolTaskExecutor executor;
    private final TransactionTemplate tx;

    public IngestProcessingService(ProcessingJobRepository jobs,
                                   ProcessingJobItemRepository items,
                                   ProcessingLogRepository logs,
                                   ExtractedContentService extraction,
                                   SourceSearchIndexSyncRepository syncRepository,
                                   @Qualifier("ingestTaskExecutor") ThreadPoolTaskExecutor executor,
                                   TransactionTemplate tx) {
        this.jobs = jobs;
        this.items = items;
        this.logs = logs;
        this.extraction = extraction;
        this.syncRepository = syncRepository;
        this.executor = executor;
        this.tx = tx;
    }

    public void enqueue(long workspaceId, List<Long> documentIds) {
        List<Long> uniqueIds = List.copyOf(new LinkedHashSet<>(documentIds));
        if (uniqueIds.isEmpty()) {
            return;
        }
        JobLaunch launch = tx.execute(status -> createJob(workspaceId, uniqueIds));
        if (launch == null) {
            throw new IllegalStateException("Could not create ingest processing job");
        }
        try {
            executor.execute(() -> run(launch));
        } catch (RuntimeException rejected) {
            failLaunch(launch, "INGEST_QUEUE_UNAVAILABLE", "文件已上傳，但自動處理佇列目前無法接受工作");
        }
    }

    private JobLaunch createJob(long workspaceId, List<Long> documentIds) {
        ProcessingJob job = jobs.create(workspaceId, UUID.randomUUID().toString(),
                ProcessingJobType.INGEST, documentIds.size());
        List<ItemRef> refs = new ArrayList<>();
        for (Long documentId : documentIds) {
            refs.add(new ItemRef(items.create(job.id(), documentId, STEP), documentId));
        }
        return new JobLaunch(workspaceId, job, List.copyOf(refs));
    }

    private void run(JobLaunch launch) {
        jobs.markRunning(launch.job().id());
        try {
            for (ItemRef item : launch.items()) {
                processOne(launch, item);
            }
        } finally {
            jobs.markCompleted(launch.job().id());
        }
    }

    private void processOne(JobLaunch launch, ItemRef item) {
        items.markRunning(item.itemId(), STEP);
        try {
            ExtractionResponse response = extraction.extractForWorkspace(
                    launch.workspaceId(), item.documentId());
            String parseStatus = response.parseStatus();
            if (DocumentStatus.PROCESSED.name().equals(parseStatus)) {
                var sync = syncRepository.find(launch.workspaceId(), item.documentId());
                if (sync.isPresent() && sync.get().status() == SourceSearchIndexSyncStatus.SYNCED) {
                    finish(launch, item, ProcessingJobItemStatus.SUCCEEDED, null,
                            "文件已完成抽取與搜尋索引同步");
                    return;
                }
                if (sync.isPresent() && sync.get().status() == SourceSearchIndexSyncStatus.INELIGIBLE) {
                    finish(launch, item, ProcessingJobItemStatus.SKIPPED, "SOURCE_NOT_SEARCHABLE",
                            "文件已完成抽取，但沒有可加入搜尋索引的內容");
                    return;
                }
                finish(launch, item, ProcessingJobItemStatus.FAILED, "SOURCE_INDEX_PENDING",
                        "文件已完成抽取，但搜尋索引尚未同步完成");
                return;
            }
            if (DocumentStatus.NEED_OCR.name().equals(parseStatus)
                    || DocumentStatus.UNSUPPORTED.name().equals(parseStatus)) {
                finish(launch, item, ProcessingJobItemStatus.SKIPPED, response.errorCode(),
                        response.errorMessage());
                return;
            }
            finish(launch, item, ProcessingJobItemStatus.FAILED,
                    response.errorCode() == null ? "INGEST_PROCESSING_FAILED" : response.errorCode(),
                    response.errorMessage() == null ? "文件自動處理失敗" : response.errorMessage());
        } catch (DocumentExtractionException failure) {
            finish(launch, item, ProcessingJobItemStatus.FAILED, failure.errorCode(), failure.getMessage());
        } catch (RuntimeException failure) {
            finish(launch, item, ProcessingJobItemStatus.FAILED,
                    "INGEST_PROCESSING_FAILED", "文件自動處理失敗");
        }
    }

    private void finish(JobLaunch launch, ItemRef item, ProcessingJobItemStatus status,
                        String errorCode, String message) {
        tx.executeWithoutResult(transaction -> {
            items.markFinished(item.itemId(), status, STEP, errorCode, message, false);
            logs.append(launch.job().id(), item.itemId(), item.documentId(), STEP, status.name(),
                    message == null ? "文件處理完成" : message,
                    "{\"reason\":\"" + (errorCode == null ? status.name() : errorCode) + "\"}");
        });
    }

    private void failLaunch(JobLaunch launch, String errorCode, String message) {
        tx.executeWithoutResult(status -> {
            for (ItemRef item : launch.items()) {
                items.markFinished(item.itemId(), ProcessingJobItemStatus.FAILED,
                        STEP, errorCode, message, false);
            }
            jobs.markFailed(launch.job().id(), launch.items().size(), 0,
                    launch.items().size(), message);
            logs.append(launch.job().id(), null, null, STEP, "FAILED",
                    message, "{\"reason\":\"" + errorCode + "\"}");
        });
    }

    private record ItemRef(long itemId, long documentId) {
    }

    private record JobLaunch(long workspaceId, ProcessingJob job, List<ItemRef> items) {
    }
}
