package org.km.llmwiki.search;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Derives Source FTS solely from canonical normalized_content after Source Chunk commit.
 * Raw content remains authoritative human-audit evidence and is never read or written here.
 */
@Service
public class SourceChunkIndexingService {

    private final SourceSearchAuthorityRepository authorityRepository;
    private final SourceSearchIndexProjectionWriter projectionWriter;
    private final SourceSearchIndexSyncRepository syncRepository;

    public SourceChunkIndexingService(SourceSearchAuthorityRepository authorityRepository,
                                      SourceSearchIndexProjectionWriter projectionWriter,
                                      SourceSearchIndexSyncRepository syncRepository) {
        this.authorityRepository = authorityRepository;
        this.projectionWriter = projectionWriter;
        this.syncRepository = syncRepository;
    }

    /** Deterministically replaces all Source FTS rows for one workspace-scoped document. */
    public SourceIndexSyncResult reindexDocument(long workspaceId, long documentId) {
        var authority = authorityRepository.findDocument(workspaceId, documentId);
        if (authority.isEmpty()) {
            projectionWriter.deleteDocument(workspaceId, documentId);
            return new SourceIndexSyncResult(SourceIndexSyncStatus.NOT_FOUND, workspaceId, documentId,
                    null, 0, 0, "Document was not found in this workspace");
        }

        List<SourceSearchDocument> eligible = eligibleDocuments(authority.get());
        String fingerprint = fingerprint(eligible);
        try {
            StoredSourceSearchIndexSync ledger = projectionWriter.replaceDocument(workspaceId, documentId,
                    eligible, fingerprint);
            return new SourceIndexSyncResult(SourceIndexSyncStatus.valueOf(ledger.status().name()),
                    workspaceId, documentId, null, eligible.size(), ledger.indexedChunkCount(), null);
        } catch (RuntimeException failure) {
            return pending(authority.get(), null, eligible, fingerprint, failure);
        }
    }

    public SourceIndexSyncResult synchronizeDocument(long workspaceId, long documentId) {
        return reindexDocument(workspaceId, documentId);
    }

    /** Refreshes or removes exactly one Source Chunk identity. */
    public SourceIndexSyncResult reindexSourceChunk(long workspaceId, long sourceChunkId) {
        var authority = authorityRepository.findDocumentByChunk(workspaceId, sourceChunkId);
        if (authority.isEmpty()) {
            projectionWriter.replaceChunk(workspaceId, sourceChunkId, null);
            return new SourceIndexSyncResult(SourceIndexSyncStatus.NOT_FOUND, workspaceId, 0,
                    sourceChunkId, 0, 0, "Source Chunk was not found in this workspace");
        }

        SourceSearchAuthorityDocument document = authority.get();
        SourceSearchDocument projection = eligibleDocuments(document).stream()
                .filter(candidate -> candidate.sourceChunkId() == sourceChunkId)
                .findFirst().orElse(null);
        try {
            projectionWriter.replaceChunk(workspaceId, sourceChunkId, projection);
            SourceIndexSyncStatus status = projection == null
                    ? SourceIndexSyncStatus.INELIGIBLE : SourceIndexSyncStatus.SYNCED;
            return new SourceIndexSyncResult(status, workspaceId, document.documentId(), sourceChunkId,
                    projection == null ? 0 : 1, projection == null ? 0 : 1, null);
        } catch (RuntimeException failure) {
            List<SourceSearchDocument> eligible = eligibleDocuments(document);
            return pending(document, sourceChunkId, eligible, fingerprint(eligible), failure);
        }
    }

    private SourceIndexSyncResult pending(SourceSearchAuthorityDocument authority, Long sourceChunkId,
                                          List<SourceSearchDocument> eligible, String fingerprint,
                                          RuntimeException failure) {
        String detail = "Source Chunk FTS sync failed: " + failure.getClass().getSimpleName()
                + ": " + safeMessage(failure);
        try {
            syncRepository.markPending(authority.workspaceId(), authority.documentId(), eligible.size(),
                    fingerprint, detail);
        } catch (RuntimeException ledgerFailure) {
            detail += "; repair ledger write failed: " + safeMessage(ledgerFailure);
        }
        return new SourceIndexSyncResult(SourceIndexSyncStatus.INDEX_PENDING, authority.workspaceId(),
                authority.documentId(), sourceChunkId, eligible.size(), 0, detail);
    }

    static List<SourceSearchDocument> eligibleDocuments(SourceSearchAuthorityDocument authority) {
        return SourceSearchFreshness.eligibleDocuments(authority);
    }

    static String fingerprint(List<SourceSearchDocument> documents) {
        return SourceSearchFreshness.fingerprint(documents);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "unspecified failure" : message;
    }
}
