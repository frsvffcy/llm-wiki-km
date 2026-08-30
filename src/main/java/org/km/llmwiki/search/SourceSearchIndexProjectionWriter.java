package org.km.llmwiki.search;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Atomic writer for rebuildable Source FTS rows and their document-level sync ledger. */
@Service
public class SourceSearchIndexProjectionWriter {

    private final FtsSearchIndexRepository ftsRepository;
    private final SourceSearchIndexSyncRepository syncRepository;

    public SourceSearchIndexProjectionWriter(FtsSearchIndexRepository ftsRepository,
                                             SourceSearchIndexSyncRepository syncRepository) {
        this.ftsRepository = ftsRepository;
        this.syncRepository = syncRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StoredSourceSearchIndexSync replaceDocument(long workspaceId, long documentId,
                                                        List<SourceSearchDocument> documents,
                                                        String canonicalFingerprint) {
        List<Long> existingSourceChunkIds = ftsRepository.findSourceChunkIds(workspaceId, documentId);
        Set<Long> eligibleSourceChunkIds = documents.stream()
                .map(SourceSearchDocument::sourceChunkId)
                .collect(Collectors.toUnmodifiableSet());
        documents.forEach(document -> {
            requireWorkspaceAndDocument(workspaceId, documentId, document);
            ftsRepository.upsertSource(document);
        });
        existingSourceChunkIds.stream()
                .filter(sourceChunkId -> !eligibleSourceChunkIds.contains(sourceChunkId))
                .forEach(sourceChunkId -> ftsRepository.deleteSource(workspaceId, sourceChunkId));
        SourceSearchIndexSyncStatus status = documents.isEmpty()
                ? SourceSearchIndexSyncStatus.INELIGIBLE : SourceSearchIndexSyncStatus.SYNCED;
        return syncRepository.markComplete(workspaceId, documentId, status,
                documents.size(), canonicalFingerprint);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void replaceChunk(long workspaceId, long sourceChunkId, SourceSearchDocument document) {
        if (document == null) {
            ftsRepository.deleteSource(workspaceId, sourceChunkId);
            return;
        }
        if (document.sourceChunkId() != sourceChunkId || document.workspaceId() != workspaceId) {
            throw new IllegalArgumentException("Source Chunk projection identity does not match request");
        }
        ftsRepository.upsertSource(document);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteDocument(long workspaceId, long documentId) {
        ftsRepository.deleteSourceDocument(workspaceId, documentId);
    }

    private static void requireWorkspaceAndDocument(long workspaceId, long documentId,
                                                    SourceSearchDocument document) {
        if (document.workspaceId() != workspaceId || document.documentId() != documentId) {
            throw new IllegalArgumentException("Source projection belongs to another document or workspace");
        }
    }
}
