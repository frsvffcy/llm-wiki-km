package org.km.llmwiki.wiki;

import org.jooq.DSLContext;
import org.km.llmwiki.persistence.jooq.generated.tables.records.WikiPublishOperationRecord;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PAGE;
import static org.km.llmwiki.persistence.jooq.generated.Tables.WIKI_PUBLISH_OPERATION;

/** jOOQ-only metadata and recovery ledger for controlled CREATE publish. */
@Repository
public class WikiPublicationRepository {

    private final DSLContext dsl;

    public WikiPublicationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public StoredWikiPublishOperation prepare(NewWikiPublishOperation operation) {
        Integer id = dsl.insertInto(WIKI_PUBLISH_OPERATION)
                .columns(WIKI_PUBLISH_OPERATION.WORKSPACE_ID, WIKI_PUBLISH_OPERATION.DRAFT_ID,
                        WIKI_PUBLISH_OPERATION.PROPOSAL_ID, WIKI_PUBLISH_OPERATION.ACTION,
                        WIKI_PUBLISH_OPERATION.KNOWLEDGE_ID, WIKI_PUBLISH_OPERATION.TARGET_PATH,
                        WIKI_PUBLISH_OPERATION.CONTENT_HASH, WIKI_PUBLISH_OPERATION.REVISION,
                        WIKI_PUBLISH_OPERATION.STATUS, WIKI_PUBLISH_OPERATION.CREATED_AT,
                        WIKI_PUBLISH_OPERATION.UPDATED_AT)
                .values((int) operation.workspaceId(), (int) operation.draftId(), (int) operation.proposalId(),
                        "CREATE", operation.knowledgeId(), operation.targetPath(), operation.contentHash(),
                        operation.revision(), WikiPublishOperationStatus.PREPARED.name(), operation.createdAt(),
                        operation.createdAt())
                .onConflictDoNothing()
                .returningResult(WIKI_PUBLISH_OPERATION.ID)
                .fetchOne(WIKI_PUBLISH_OPERATION.ID);
        if (id == null) {
            throw new WikiPublishException(WikiPublishException.Reason.OPERATION_CONFLICT,
                    "A CREATE publish reservation already exists for this Draft or target");
        }
        return require(operation.workspaceId(), id.longValue());
    }

    public Optional<StoredWikiPublishOperation> findByDraft(long workspaceId, long draftId) {
        return dsl.selectFrom(WIKI_PUBLISH_OPERATION)
                .where(WIKI_PUBLISH_OPERATION.WORKSPACE_ID.eq((int) workspaceId))
                .and(WIKI_PUBLISH_OPERATION.DRAFT_ID.eq((int) draftId))
                .fetchOptional(this::map);
    }

    public void markFileCommitted(long workspaceId, long operationId) {
        transition(workspaceId, operationId, WikiPublishOperationStatus.PREPARED,
                WikiPublishOperationStatus.FILE_COMMITTED, null);
    }

    public void markRolledBack(long workspaceId, long operationId, String detail) {
        markFailure(workspaceId, operationId, WikiPublishOperationStatus.ROLLED_BACK, detail);
    }

    public void markReconciliationRequired(long workspaceId, long operationId, String detail) {
        markFailure(workspaceId, operationId, WikiPublishOperationStatus.RECONCILIATION_REQUIRED, detail);
    }

    public long insertKnowledgePage(StoredWikiDraft draft, StoredWikiPublishOperation operation,
                                    String publishedAt) {
        Integer id = dsl.insertInto(KNOWLEDGE_PAGE)
                .columns(KNOWLEDGE_PAGE.WORKSPACE_ID, KNOWLEDGE_PAGE.KNOWLEDGE_ID,
                        KNOWLEDGE_PAGE.TITLE, KNOWLEDGE_PAGE.NORMALIZED_TITLE, KNOWLEDGE_PAGE.TYPE,
                        KNOWLEDGE_PAGE.MARKDOWN_PATH, KNOWLEDGE_PAGE.STATUS, KNOWLEDGE_PAGE.CONTENT_HASH,
                        KNOWLEDGE_PAGE.REVISION, KNOWLEDGE_PAGE.PROPOSAL_ID, KNOWLEDGE_PAGE.DRAFT_ID,
                        KNOWLEDGE_PAGE.PUBLISHED_AT, KNOWLEDGE_PAGE.CREATED_AT, KNOWLEDGE_PAGE.UPDATED_AT)
                .values((int) draft.workspaceId(), operation.knowledgeId(), draft.title(),
                        WikiTargetReference.normalizeTitle(draft.title()), draft.pageType().name(),
                        operation.targetPath(), PageStatus.PUBLISHED.name(), operation.contentHash(),
                        operation.revision(), (int) draft.proposalId(), (int) draft.id(), publishedAt,
                        publishedAt, publishedAt)
                .returningResult(KNOWLEDGE_PAGE.ID)
                .fetchOne(KNOWLEDGE_PAGE.ID);
        if (id == null) {
            throw new IllegalStateException("Knowledge page insert did not return a generated id");
        }
        return id.longValue();
    }

    public void complete(long workspaceId, long operationId, long knowledgePageId, String completedAt) {
        int updated = dsl.update(WIKI_PUBLISH_OPERATION)
                .set(WIKI_PUBLISH_OPERATION.STATUS, WikiPublishOperationStatus.COMPLETED.name())
                .set(WIKI_PUBLISH_OPERATION.KNOWLEDGE_PAGE_ID, Math.toIntExact(knowledgePageId))
                .set(WIKI_PUBLISH_OPERATION.UPDATED_AT, completedAt)
                .set(WIKI_PUBLISH_OPERATION.COMPLETED_AT, completedAt)
                .where(WIKI_PUBLISH_OPERATION.ID.eq((int) operationId))
                .and(WIKI_PUBLISH_OPERATION.WORKSPACE_ID.eq((int) workspaceId))
                .and(WIKI_PUBLISH_OPERATION.STATUS.eq(WikiPublishOperationStatus.FILE_COMMITTED.name()))
                .execute();
        requireSingle(updated, "CREATE publish operation could not be completed");
    }

    private void markFailure(long workspaceId, long operationId, WikiPublishOperationStatus next,
                             String detail) {
        String safeDetail = detail == null || detail.isBlank() ? "Unspecified publish failure"
                : detail.substring(0, Math.min(detail.length(), 1000));
        int updated = dsl.update(WIKI_PUBLISH_OPERATION)
                .set(WIKI_PUBLISH_OPERATION.STATUS, next.name())
                .set(WIKI_PUBLISH_OPERATION.FAILURE_DETAIL, safeDetail)
                .set(WIKI_PUBLISH_OPERATION.UPDATED_AT, now())
                .where(WIKI_PUBLISH_OPERATION.ID.eq((int) operationId))
                .and(WIKI_PUBLISH_OPERATION.WORKSPACE_ID.eq((int) workspaceId))
                .and(WIKI_PUBLISH_OPERATION.STATUS.in(WikiPublishOperationStatus.PREPARED.name(),
                        WikiPublishOperationStatus.FILE_COMMITTED.name()))
                .execute();
        requireSingle(updated, "CREATE publish failure outcome could not be persisted");
    }

    private void transition(long workspaceId, long operationId, WikiPublishOperationStatus expected,
                            WikiPublishOperationStatus next, String failureDetail) {
        int updated = dsl.update(WIKI_PUBLISH_OPERATION)
                .set(WIKI_PUBLISH_OPERATION.STATUS, next.name())
                .set(WIKI_PUBLISH_OPERATION.FAILURE_DETAIL, failureDetail)
                .set(WIKI_PUBLISH_OPERATION.UPDATED_AT, now())
                .where(WIKI_PUBLISH_OPERATION.ID.eq((int) operationId))
                .and(WIKI_PUBLISH_OPERATION.WORKSPACE_ID.eq((int) workspaceId))
                .and(WIKI_PUBLISH_OPERATION.STATUS.eq(expected.name()))
                .execute();
        requireSingle(updated, "CREATE publish operation did not have expected state " + expected);
    }

    private StoredWikiPublishOperation require(long workspaceId, long operationId) {
        return dsl.selectFrom(WIKI_PUBLISH_OPERATION)
                .where(WIKI_PUBLISH_OPERATION.ID.eq((int) operationId))
                .and(WIKI_PUBLISH_OPERATION.WORKSPACE_ID.eq((int) workspaceId))
                .fetchOptional(this::map)
                .orElseThrow(() -> new IllegalStateException("CREATE publish operation was not persisted"));
    }

    private StoredWikiPublishOperation map(WikiPublishOperationRecord record) {
        Integer pageId = record.getKnowledgePageId();
        return new StoredWikiPublishOperation(record.getId().longValue(), record.getWorkspaceId().longValue(),
                record.getDraftId().longValue(), record.getProposalId().longValue(), record.getKnowledgeId(),
                record.getTargetPath(), record.getContentHash(), record.getRevision(),
                WikiPublishOperationStatus.valueOf(record.getStatus()),
                pageId == null ? null : pageId.longValue(), record.getFailureDetail(), record.getCreatedAt(),
                record.getUpdatedAt(), record.getCompletedAt());
    }

    private static void requireSingle(int updated, String message) {
        if (updated != 1) {
            throw new IllegalStateException(message);
        }
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }
}
