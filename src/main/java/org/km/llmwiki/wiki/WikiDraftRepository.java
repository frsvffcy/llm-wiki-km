package org.km.llmwiki.wiki;

import org.jooq.DSLContext;
import org.km.llmwiki.ai.LlmProposalAction;
import org.km.llmwiki.persistence.jooq.generated.tables.records.WikiDraftRecord;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.km.llmwiki.persistence.jooq.generated.Tables.WIKI_DRAFT;

/** jOOQ-only persistence boundary for Wiki Draft review snapshots and lifecycle transitions. */
@Repository
public class WikiDraftRepository {

    private final DSLContext dsl;

    public WikiDraftRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long insertDraft(NewStoredWikiDraft draft) {
        String now = now();
        Integer id = dsl.insertInto(WIKI_DRAFT)
                .columns(WIKI_DRAFT.WORKSPACE_ID, WIKI_DRAFT.PROPOSAL_ID, WIKI_DRAFT.ACTION,
                        WIKI_DRAFT.PAGE_TYPE, WIKI_DRAFT.TITLE, WIKI_DRAFT.TARGET_TITLE,
                        WIKI_DRAFT.TARGET_PAGE_TYPE, WIKI_DRAFT.TARGET_KNOWLEDGE_ID,
                        WIKI_DRAFT.TARGET_PATH, WIKI_DRAFT.STATUS, WIKI_DRAFT.EXPECTED_CONTENT_HASH,
                        WIKI_DRAFT.BASE_CONTENT_HASH, WIKI_DRAFT.RENDERED_CONTENT_HASH,
                        WIKI_DRAFT.INPUT_HASH, WIKI_DRAFT.STRUCTURED_DRAFT_JSON,
                        WIKI_DRAFT.BASE_CONTENT, WIKI_DRAFT.RENDERED_CONTENT,
                        WIKI_DRAFT.REGENERATED_FROM_DRAFT_ID, WIKI_DRAFT.CREATED_AT, WIKI_DRAFT.UPDATED_AT)
                .values((int) draft.workspaceId(), (int) draft.proposalId(), draft.action().name(),
                        draft.pageType().name(), draft.title(), draft.targetTitle(), draft.targetPageType().name(),
                        draft.targetKnowledgeId(), draft.targetPath(),
                        WikiDraftStatus.DRAFT.name(), draft.expectedContentHash(), draft.baseContentHash(),
                        draft.renderedContentHash(), draft.inputHash(), draft.structuredDraftJson(),
                        draft.baseContent(), draft.renderedContent(), nullableInteger(draft.regeneratedFromDraftId()),
                        now, now)
                .returningResult(WIKI_DRAFT.ID)
                .fetchOne(WIKI_DRAFT.ID);
        if (id == null) {
            throw new IllegalStateException("Wiki Draft insert did not return a generated id");
        }
        return id.longValue();
    }

    public Optional<StoredWikiDraft> findById(long workspaceId, long draftId) {
        return dsl.selectFrom(WIKI_DRAFT)
                .where(WIKI_DRAFT.ID.eq((int) draftId))
                .and(WIKI_DRAFT.WORKSPACE_ID.eq((int) workspaceId))
                .fetchOptional(this::map);
    }

    public void transition(long workspaceId, long draftId, WikiDraftStatus expected, WikiDraftStatus next,
                           WikiDraftInvalidationReason reason) {
        expected.requireTransitionTo(next);
        if ((next == WikiDraftStatus.INVALIDATED) != (reason != null)) {
            throw new IllegalArgumentException("Wiki Draft invalidation transition requires exactly one reason");
        }
        int updated = dsl.update(WIKI_DRAFT)
                .set(WIKI_DRAFT.STATUS, next.name())
                .set(WIKI_DRAFT.INVALIDATED_REASON, reason == null ? null : reason.name())
                .set(WIKI_DRAFT.UPDATED_AT, now())
                .where(WIKI_DRAFT.ID.eq((int) draftId))
                .and(WIKI_DRAFT.WORKSPACE_ID.eq((int) workspaceId))
                .and(WIKI_DRAFT.STATUS.eq(expected.name()))
                .execute();
        if (updated != 1) {
            throw new WikiDraftLifecycleException("Wiki Draft was missing or did not have expected status "
                    + expected);
        }
    }

    public void markPublished(long workspaceId, long draftId, String publishedPath, String contentHash,
                              int revision, String publishedAt) {
        WikiDraftStatus.READY.requireTransitionTo(WikiDraftStatus.PUBLISHED);
        if (publishedPath == null || publishedPath.isBlank() || contentHash == null || contentHash.isBlank()
                || revision != 1 || publishedAt == null || publishedAt.isBlank()) {
            throw new IllegalArgumentException("Published Wiki Draft metadata is incomplete");
        }
        int updated = dsl.update(WIKI_DRAFT)
                .set(WIKI_DRAFT.STATUS, WikiDraftStatus.PUBLISHED.name())
                .set(WIKI_DRAFT.PUBLISHED_PATH, publishedPath)
                .set(WIKI_DRAFT.PUBLISHED_CONTENT_HASH, contentHash)
                .set(WIKI_DRAFT.PUBLISHED_REVISION, revision)
                .set(WIKI_DRAFT.PUBLISHED_AT, publishedAt)
                .set(WIKI_DRAFT.UPDATED_AT, publishedAt)
                .where(WIKI_DRAFT.ID.eq((int) draftId))
                .and(WIKI_DRAFT.WORKSPACE_ID.eq((int) workspaceId))
                .and(WIKI_DRAFT.STATUS.eq(WikiDraftStatus.READY.name()))
                .execute();
        if (updated != 1) {
            throw new WikiDraftLifecycleException("Wiki Draft was missing or was no longer READY at publish");
        }
    }

    private StoredWikiDraft map(WikiDraftRecord record) {
        String reason = record.getInvalidatedReason();
        Integer parentId = record.getRegeneratedFromDraftId();
        return new StoredWikiDraft(record.getId().longValue(), record.getWorkspaceId().longValue(),
                record.getProposalId().longValue(), LlmProposalAction.valueOf(record.getAction()),
                WikiPageType.valueOf(record.getPageType()), record.getTitle(), record.getTargetTitle(),
                WikiPageType.valueOf(record.getTargetPageType()), record.getTargetKnowledgeId(),
                record.getTargetPath(), WikiDraftStatus.valueOf(record.getStatus()),
                record.getExpectedContentHash(), record.getBaseContentHash(), record.getRenderedContentHash(),
                record.getInputHash(), record.getStructuredDraftJson(), record.getBaseContent(),
                record.getRenderedContent(), reason == null ? null : WikiDraftInvalidationReason.valueOf(reason),
                parentId == null ? null : parentId.longValue(), record.getCreatedAt(), record.getUpdatedAt());
    }

    private static Integer nullableInteger(Long value) {
        return value == null ? null : Math.toIntExact(value);
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }
}
