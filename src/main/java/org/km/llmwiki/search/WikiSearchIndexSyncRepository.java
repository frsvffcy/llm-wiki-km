package org.km.llmwiki.search;

import org.jooq.DSLContext;
import org.km.llmwiki.persistence.jooq.generated.tables.records.KnowledgeSearchIndexSyncRecord;
import org.km.llmwiki.wiki.StoredPublishedWiki;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_SEARCH_INDEX_SYNC;

/** jOOQ persistence boundary for the non-transactional FTS repair ledger. */
@Repository
public class WikiSearchIndexSyncRepository {

    private final DSLContext dsl;

    public WikiSearchIndexSyncRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public StoredWikiSearchIndexSync markSynced(StoredPublishedWiki page) {
        String now = now();
        dsl.insertInto(KNOWLEDGE_SEARCH_INDEX_SYNC)
                .columns(KNOWLEDGE_SEARCH_INDEX_SYNC.WORKSPACE_ID,
                        KNOWLEDGE_SEARCH_INDEX_SYNC.KNOWLEDGE_PAGE_ID,
                        KNOWLEDGE_SEARCH_INDEX_SYNC.KNOWLEDGE_ID,
                        KNOWLEDGE_SEARCH_INDEX_SYNC.STATUS,
                        KNOWLEDGE_SEARCH_INDEX_SYNC.CONTENT_HASH,
                        KNOWLEDGE_SEARCH_INDEX_SYNC.INDEXED_CONTENT_HASH,
                        KNOWLEDGE_SEARCH_INDEX_SYNC.FAILURE_DETAIL,
                        KNOWLEDGE_SEARCH_INDEX_SYNC.UPDATED_AT)
                .values(Math.toIntExact(page.workspaceId()), Math.toIntExact(page.id()), page.knowledgeId(),
                        WikiSearchIndexSyncStatus.SYNCED.name(), page.contentHash(), page.contentHash(),
                        null, now)
                .onConflict(KNOWLEDGE_SEARCH_INDEX_SYNC.WORKSPACE_ID,
                        KNOWLEDGE_SEARCH_INDEX_SYNC.KNOWLEDGE_PAGE_ID)
                .doUpdate()
                .set(KNOWLEDGE_SEARCH_INDEX_SYNC.KNOWLEDGE_ID, page.knowledgeId())
                .set(KNOWLEDGE_SEARCH_INDEX_SYNC.STATUS, WikiSearchIndexSyncStatus.SYNCED.name())
                .set(KNOWLEDGE_SEARCH_INDEX_SYNC.CONTENT_HASH, page.contentHash())
                .set(KNOWLEDGE_SEARCH_INDEX_SYNC.INDEXED_CONTENT_HASH, page.contentHash())
                .set(KNOWLEDGE_SEARCH_INDEX_SYNC.FAILURE_DETAIL, (String) null)
                .set(KNOWLEDGE_SEARCH_INDEX_SYNC.UPDATED_AT, now)
                .execute();
        return require(page.workspaceId(), page.id());
    }

    public StoredWikiSearchIndexSync markPending(StoredPublishedWiki page,
                                                  WikiSearchIndexSyncStatus status,
                                                  String detail) {
        if (status != WikiSearchIndexSyncStatus.INDEX_PENDING
                && status != WikiSearchIndexSyncStatus.DRIFT) {
            throw new IllegalArgumentException("Only pending or drift states can be recorded");
        }
        String now = now();
        String safeDetail = detail == null || detail.isBlank() ? "Unspecified Wiki FTS sync failure"
                : detail.substring(0, Math.min(detail.length(), 1000));
        dsl.insertInto(KNOWLEDGE_SEARCH_INDEX_SYNC)
                .columns(KNOWLEDGE_SEARCH_INDEX_SYNC.WORKSPACE_ID,
                        KNOWLEDGE_SEARCH_INDEX_SYNC.KNOWLEDGE_PAGE_ID,
                        KNOWLEDGE_SEARCH_INDEX_SYNC.KNOWLEDGE_ID,
                        KNOWLEDGE_SEARCH_INDEX_SYNC.STATUS,
                        KNOWLEDGE_SEARCH_INDEX_SYNC.CONTENT_HASH,
                        KNOWLEDGE_SEARCH_INDEX_SYNC.FAILURE_DETAIL,
                        KNOWLEDGE_SEARCH_INDEX_SYNC.UPDATED_AT)
                .values(Math.toIntExact(page.workspaceId()), Math.toIntExact(page.id()), page.knowledgeId(),
                        status.name(), page.contentHash(), safeDetail, now)
                .onConflict(KNOWLEDGE_SEARCH_INDEX_SYNC.WORKSPACE_ID,
                        KNOWLEDGE_SEARCH_INDEX_SYNC.KNOWLEDGE_PAGE_ID)
                .doUpdate()
                .set(KNOWLEDGE_SEARCH_INDEX_SYNC.KNOWLEDGE_ID, page.knowledgeId())
                .set(KNOWLEDGE_SEARCH_INDEX_SYNC.STATUS, status.name())
                .set(KNOWLEDGE_SEARCH_INDEX_SYNC.CONTENT_HASH, page.contentHash())
                .set(KNOWLEDGE_SEARCH_INDEX_SYNC.FAILURE_DETAIL, safeDetail)
                .set(KNOWLEDGE_SEARCH_INDEX_SYNC.UPDATED_AT, now)
                .execute();
        return require(page.workspaceId(), page.id());
    }

    public Optional<StoredWikiSearchIndexSync> find(long workspaceId, long knowledgePageId) {
        return dsl.selectFrom(KNOWLEDGE_SEARCH_INDEX_SYNC)
                .where(KNOWLEDGE_SEARCH_INDEX_SYNC.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(KNOWLEDGE_SEARCH_INDEX_SYNC.KNOWLEDGE_PAGE_ID.eq(Math.toIntExact(knowledgePageId)))
                .fetchOptional(this::map);
    }

    private StoredWikiSearchIndexSync require(long workspaceId, long knowledgePageId) {
        return find(workspaceId, knowledgePageId)
                .orElseThrow(() -> new IllegalStateException("Wiki FTS sync ledger row was not persisted"));
    }

    private StoredWikiSearchIndexSync map(KnowledgeSearchIndexSyncRecord record) {
        return new StoredWikiSearchIndexSync(record.getWorkspaceId().longValue(),
                record.getKnowledgePageId().longValue(), record.getKnowledgeId(),
                WikiSearchIndexSyncStatus.valueOf(record.getStatus()), record.getContentHash(),
                record.getIndexedContentHash(), record.getFailureDetail(), record.getUpdatedAt());
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }
}
