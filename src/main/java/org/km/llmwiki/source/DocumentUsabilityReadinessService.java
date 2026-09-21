package org.km.llmwiki.source;

import org.km.llmwiki.processing.ProcessingJobItemRepository;
import org.km.llmwiki.search.SearchServingConsistencyGate;
import org.km.llmwiki.search.SourceSearchIndexSyncRepository;
import org.km.llmwiki.search.SourceSearchIndexSyncStatus;
import org.springframework.stereotype.Service;

/**
 * Single application boundary for Inbox usability. Browser code must not derive
 * search readiness from parseStatus or projection details on its own.
 */
@Service
public class DocumentUsabilityReadinessService {

    private final ProcessingJobItemRepository jobItems;
    private final SourceSearchIndexSyncRepository syncRepository;
    private final SearchServingConsistencyGate consistencyGate;

    public DocumentUsabilityReadinessService(ProcessingJobItemRepository jobItems,
                                             SourceSearchIndexSyncRepository syncRepository,
                                             SearchServingConsistencyGate consistencyGate) {
        this.jobItems = jobItems;
        this.syncRepository = syncRepository;
        this.consistencyGate = consistencyGate;
    }

    public DocumentUsabilityReadiness resolve(long workspaceId, InboxDocumentRow document) {
        if (DocumentStatus.DUPLICATE.name().equals(document.status())) {
            return readiness(DocumentUsabilityReadiness.Status.DUPLICATE, false,
                    DocumentUsabilityReadiness.NextAction.USE_EXISTING_DOCUMENT);
        }

        if (jobItems.findActiveIngestState(workspaceId, document.documentId()).isPresent()) {
            return readiness(DocumentUsabilityReadiness.Status.PROCESSING, false,
                    DocumentUsabilityReadiness.NextAction.WAIT);
        }

        String parseStatus = document.parseStatus();
        if (parseStatus == null || parseStatus.isBlank()) {
            return readiness(DocumentUsabilityReadiness.Status.NOT_PROCESSED, false,
                    DocumentUsabilityReadiness.NextAction.RETRY_PROCESSING);
        }
        if (DocumentStatus.NEED_OCR.name().equals(parseStatus)) {
            return readiness(DocumentUsabilityReadiness.Status.NEED_OCR, false,
                    DocumentUsabilityReadiness.NextAction.PROVIDE_OCR);
        }
        if (DocumentStatus.UNSUPPORTED.name().equals(parseStatus)) {
            return readiness(DocumentUsabilityReadiness.Status.UNSUPPORTED, false,
                    DocumentUsabilityReadiness.NextAction.NONE);
        }
        if (DocumentStatus.FAILED.name().equals(parseStatus)) {
            return readiness(DocumentUsabilityReadiness.Status.FAILED, false,
                    DocumentUsabilityReadiness.NextAction.RETRY_PROCESSING);
        }
        if (!DocumentStatus.PROCESSED.name().equals(parseStatus)) {
            return readiness(DocumentUsabilityReadiness.Status.FAILED, false,
                    DocumentUsabilityReadiness.NextAction.RETRY_PROCESSING);
        }

        var sync = syncRepository.find(workspaceId, document.documentId());
        if (sync.isEmpty() || sync.get().status() == SourceSearchIndexSyncStatus.INDEX_PENDING) {
            return readiness(DocumentUsabilityReadiness.Status.INDEX_PENDING, false,
                    DocumentUsabilityReadiness.NextAction.RETRY_PROCESSING);
        }
        if (sync.get().status() == SourceSearchIndexSyncStatus.INELIGIBLE) {
            return readiness(DocumentUsabilityReadiness.Status.NOT_SEARCHABLE, false,
                    DocumentUsabilityReadiness.NextAction.NONE);
        }
        if (sync.get().status() == SourceSearchIndexSyncStatus.SYNCED
                && consistencyGate.isDocumentFresh(workspaceId, document.documentId())) {
            return readiness(DocumentUsabilityReadiness.Status.READY_TO_USE, true,
                    DocumentUsabilityReadiness.NextAction.START_USING);
        }
        return readiness(DocumentUsabilityReadiness.Status.INDEX_PENDING, false,
                DocumentUsabilityReadiness.NextAction.RETRY_PROCESSING);
    }

    private static DocumentUsabilityReadiness readiness(DocumentUsabilityReadiness.Status status,
                                                        boolean searchReady,
                                                        DocumentUsabilityReadiness.NextAction action) {
        return new DocumentUsabilityReadiness(status, searchReady, action);
    }
}
