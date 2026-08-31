package org.km.llmwiki.search;

import org.jooq.DSLContext;
import org.km.llmwiki.persistence.jooq.generated.tables.records.SourceSearchIndexSyncRecord;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.List;

import static org.km.llmwiki.persistence.jooq.generated.Tables.SOURCE_SEARCH_INDEX_SYNC;

/** jOOQ persistence boundary for the document-level Source FTS repair ledger. */
@Repository
public class SourceSearchIndexSyncRepository {

    private final DSLContext dsl;

    public SourceSearchIndexSyncRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public StoredSourceSearchIndexSync markComplete(long workspaceId, long documentId,
                                                     SourceSearchIndexSyncStatus status,
                                                     int eligibleChunkCount, String fingerprint) {
        if (status != SourceSearchIndexSyncStatus.SYNCED
                && status != SourceSearchIndexSyncStatus.INELIGIBLE) {
            throw new IllegalArgumentException("Only complete Source index states can be recorded");
        }
        int indexedChunkCount = status == SourceSearchIndexSyncStatus.SYNCED ? eligibleChunkCount : 0;
        String indexedFingerprint = status == SourceSearchIndexSyncStatus.SYNCED ? fingerprint : null;
        String now = now();
        dsl.insertInto(SOURCE_SEARCH_INDEX_SYNC)
                .columns(SOURCE_SEARCH_INDEX_SYNC.WORKSPACE_ID,
                        SOURCE_SEARCH_INDEX_SYNC.DOCUMENT_ID,
                        SOURCE_SEARCH_INDEX_SYNC.STATUS,
                        SOURCE_SEARCH_INDEX_SYNC.ELIGIBLE_CHUNK_COUNT,
                        SOURCE_SEARCH_INDEX_SYNC.INDEXED_CHUNK_COUNT,
                        SOURCE_SEARCH_INDEX_SYNC.CANONICAL_FINGERPRINT,
                        SOURCE_SEARCH_INDEX_SYNC.INDEXED_FINGERPRINT,
                        SOURCE_SEARCH_INDEX_SYNC.PROJECTION_VERSION,
                        SOURCE_SEARCH_INDEX_SYNC.FAILURE_DETAIL,
                        SOURCE_SEARCH_INDEX_SYNC.UPDATED_AT)
                .values(Math.toIntExact(workspaceId), Math.toIntExact(documentId), status.name(),
                        eligibleChunkCount, indexedChunkCount, fingerprint, indexedFingerprint,
                        CjkBigramProjector.VERSION, null, now)
                .onConflict(SOURCE_SEARCH_INDEX_SYNC.WORKSPACE_ID,
                        SOURCE_SEARCH_INDEX_SYNC.DOCUMENT_ID)
                .doUpdate()
                .set(SOURCE_SEARCH_INDEX_SYNC.STATUS, status.name())
                .set(SOURCE_SEARCH_INDEX_SYNC.ELIGIBLE_CHUNK_COUNT, eligibleChunkCount)
                .set(SOURCE_SEARCH_INDEX_SYNC.INDEXED_CHUNK_COUNT, indexedChunkCount)
                .set(SOURCE_SEARCH_INDEX_SYNC.CANONICAL_FINGERPRINT, fingerprint)
                .set(SOURCE_SEARCH_INDEX_SYNC.INDEXED_FINGERPRINT, indexedFingerprint)
                .set(SOURCE_SEARCH_INDEX_SYNC.PROJECTION_VERSION, CjkBigramProjector.VERSION)
                .set(SOURCE_SEARCH_INDEX_SYNC.FAILURE_DETAIL, (String) null)
                .set(SOURCE_SEARCH_INDEX_SYNC.UPDATED_AT, now)
                .execute();
        return require(workspaceId, documentId);
    }

    /** Writes repair state in a new transaction after the failed projection transaction rolled back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StoredSourceSearchIndexSync markPending(long workspaceId, long documentId,
                                                    int eligibleChunkCount,
                                                    String canonicalFingerprint, String detail) {
        String safeDetail = detail == null || detail.isBlank() ? "Unspecified Source FTS sync failure"
                : detail.substring(0, Math.min(detail.length(), 1000));
        String now = now();
        dsl.insertInto(SOURCE_SEARCH_INDEX_SYNC)
                .columns(SOURCE_SEARCH_INDEX_SYNC.WORKSPACE_ID,
                        SOURCE_SEARCH_INDEX_SYNC.DOCUMENT_ID,
                        SOURCE_SEARCH_INDEX_SYNC.STATUS,
                        SOURCE_SEARCH_INDEX_SYNC.ELIGIBLE_CHUNK_COUNT,
                        SOURCE_SEARCH_INDEX_SYNC.INDEXED_CHUNK_COUNT,
                        SOURCE_SEARCH_INDEX_SYNC.CANONICAL_FINGERPRINT,
                        SOURCE_SEARCH_INDEX_SYNC.PROJECTION_VERSION,
                        SOURCE_SEARCH_INDEX_SYNC.FAILURE_DETAIL,
                        SOURCE_SEARCH_INDEX_SYNC.UPDATED_AT)
                .values(Math.toIntExact(workspaceId), Math.toIntExact(documentId),
                        SourceSearchIndexSyncStatus.INDEX_PENDING.name(), eligibleChunkCount, 0,
                        canonicalFingerprint, CjkBigramProjector.VERSION, safeDetail, now)
                .onConflict(SOURCE_SEARCH_INDEX_SYNC.WORKSPACE_ID,
                        SOURCE_SEARCH_INDEX_SYNC.DOCUMENT_ID)
                .doUpdate()
                .set(SOURCE_SEARCH_INDEX_SYNC.STATUS, SourceSearchIndexSyncStatus.INDEX_PENDING.name())
                .set(SOURCE_SEARCH_INDEX_SYNC.ELIGIBLE_CHUNK_COUNT, eligibleChunkCount)
                .set(SOURCE_SEARCH_INDEX_SYNC.CANONICAL_FINGERPRINT, canonicalFingerprint)
                .set(SOURCE_SEARCH_INDEX_SYNC.PROJECTION_VERSION, CjkBigramProjector.VERSION)
                .set(SOURCE_SEARCH_INDEX_SYNC.FAILURE_DETAIL, safeDetail)
                .set(SOURCE_SEARCH_INDEX_SYNC.UPDATED_AT, now)
                .execute();
        return require(workspaceId, documentId);
    }

    public Optional<StoredSourceSearchIndexSync> find(long workspaceId, long documentId) {
        return dsl.selectFrom(SOURCE_SEARCH_INDEX_SYNC)
                .where(SOURCE_SEARCH_INDEX_SYNC.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(SOURCE_SEARCH_INDEX_SYNC.DOCUMENT_ID.eq(Math.toIntExact(documentId)))
                .fetchOptional(this::map);
    }

    public List<StoredSourceSearchIndexSync> findAll(long workspaceId) {
        return dsl.selectFrom(SOURCE_SEARCH_INDEX_SYNC)
                .where(SOURCE_SEARCH_INDEX_SYNC.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .orderBy(SOURCE_SEARCH_INDEX_SYNC.DOCUMENT_ID.asc())
                .fetch(this::map);
    }

    public void clearWorkspace(long workspaceId) {
        dsl.deleteFrom(SOURCE_SEARCH_INDEX_SYNC)
                .where(SOURCE_SEARCH_INDEX_SYNC.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .execute();
    }

    private StoredSourceSearchIndexSync require(long workspaceId, long documentId) {
        return find(workspaceId, documentId)
                .orElseThrow(() -> new IllegalStateException("Source FTS sync ledger row was not persisted"));
    }

    private StoredSourceSearchIndexSync map(SourceSearchIndexSyncRecord record) {
        return new StoredSourceSearchIndexSync(record.getWorkspaceId().longValue(),
                record.getDocumentId().longValue(), SourceSearchIndexSyncStatus.valueOf(record.getStatus()),
                record.getEligibleChunkCount(), record.getIndexedChunkCount(),
                record.getCanonicalFingerprint(), record.getIndexedFingerprint(),
                record.getProjectionVersion(), record.getFailureDetail(), record.getUpdatedAt());
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }
}
