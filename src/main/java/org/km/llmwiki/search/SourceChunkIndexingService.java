package org.km.llmwiki.search;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
        if (!SourceSearchEligibilityPolicy.documentEligible(authority)) {
            return List.of();
        }
        List<SourceSearchDocument> result = new ArrayList<>();
        for (SourceSearchAuthorityChunk chunk : authority.chunks()) {
            if (!SourceSearchEligibilityPolicy.chunkEligible(chunk)) {
                continue;
            }
            result.add(new SourceSearchDocument(authority.workspaceId(), chunk.sourceChunkId(),
                    authority.documentId(), chunk.chunkNo(), chunk.pageNo(), chunk.normalizedContent(),
                    chunk.section(), chunk.headingPath(), chunk.contentHash()));
        }
        return List.copyOf(result);
    }

    static String fingerprint(List<SourceSearchDocument> documents) {
        MessageDigest digest = sha256Digest();
        for (SourceSearchDocument document : documents) {
            update(digest, Long.toString(document.sourceChunkId()));
            update(digest, Integer.toString(document.chunkNo()));
            update(digest, document.pageNo() == null ? "" : document.pageNo().toString());
            update(digest, document.section());
            update(digest, document.headingPath());
            update(digest, document.normalizedContent());
            update(digest, document.contentHash());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) ';');
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "unspecified failure" : message;
    }
}
