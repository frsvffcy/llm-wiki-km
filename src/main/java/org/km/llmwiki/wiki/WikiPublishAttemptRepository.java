package org.km.llmwiki.wiki;

import org.jooq.DSLContext;
import org.jooq.UpdateSetMoreStep;
import org.km.llmwiki.ai.LlmProposalAction;
import org.km.llmwiki.persistence.jooq.generated.tables.records.WikiPublishAttemptRecord;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

import static org.km.llmwiki.persistence.jooq.generated.Tables.WIKI_PUBLISH_ATTEMPT;

/** jOOQ-only append audit for every request against an existing publishable Draft identity. */
@Repository
public class WikiPublishAttemptRepository {

    private final DSLContext dsl;

    public WikiPublishAttemptRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public StoredWikiPublishAttempt start(StoredWikiDraft draft) {
        String startedAt = now();
        String key = idempotencyKey(draft);
        Integer id = dsl.insertInto(WIKI_PUBLISH_ATTEMPT)
                .columns(WIKI_PUBLISH_ATTEMPT.WORKSPACE_ID, WIKI_PUBLISH_ATTEMPT.DRAFT_ID,
                        WIKI_PUBLISH_ATTEMPT.PROPOSAL_ID, WIKI_PUBLISH_ATTEMPT.ACTION,
                        WIKI_PUBLISH_ATTEMPT.IDEMPOTENCY_KEY, WIKI_PUBLISH_ATTEMPT.TARGET_PATH,
                        WIKI_PUBLISH_ATTEMPT.BEFORE_CONTENT_HASH, WIKI_PUBLISH_ATTEMPT.STARTED_AT)
                .values(Math.toIntExact(draft.workspaceId()), Math.toIntExact(draft.id()),
                        Math.toIntExact(draft.proposalId()), draft.action().name(), key, draft.targetPath(),
                        draft.action() == LlmProposalAction.MERGE ? draft.expectedContentHash() : null, startedAt)
                .returningResult(WIKI_PUBLISH_ATTEMPT.ID)
                .fetchOne(WIKI_PUBLISH_ATTEMPT.ID);
        if (id == null) {
            throw new WikiPublishException(WikiPublishException.Reason.METADATA_FAILURE,
                    "Publish attempt audit could not be started");
        }
        return new StoredWikiPublishAttempt(id.longValue(), draft.workspaceId(), draft.id(), draft.proposalId(),
                key, startedAt);
    }

    public void complete(StoredWikiPublishAttempt attempt, WikiPublishResultType result,
                         StoredWikiPublishOperation operation) {
        int updated = baseCompletion(attempt, operation)
                .set(WIKI_PUBLISH_ATTEMPT.RESULT, result.name())
                .set(WIKI_PUBLISH_ATTEMPT.FINISHED_AT, now())
                .where(WIKI_PUBLISH_ATTEMPT.ID.eq(Math.toIntExact(attempt.id())))
                .and(WIKI_PUBLISH_ATTEMPT.WORKSPACE_ID.eq(Math.toIntExact(attempt.workspaceId())))
                .and(WIKI_PUBLISH_ATTEMPT.RESULT.isNull())
                .execute();
        requireSingle(updated);
    }

    public void fail(StoredWikiPublishAttempt attempt, WikiPublishFailure failure,
                     StoredWikiPublishOperation operation) {
        int updated = baseCompletion(attempt, operation)
                .set(WIKI_PUBLISH_ATTEMPT.RESULT, failure.result().name())
                .set(WIKI_PUBLISH_ATTEMPT.FAILURE_CATEGORY, failure.category().name())
                .set(WIKI_PUBLISH_ATTEMPT.FAILURE_CODE, failure.code())
                .set(WIKI_PUBLISH_ATTEMPT.FAILURE_STAGE, failure.stage().name())
                .set(WIKI_PUBLISH_ATTEMPT.ERROR_DETAIL, failure.detail())
                .set(WIKI_PUBLISH_ATTEMPT.FINISHED_AT, now())
                .where(WIKI_PUBLISH_ATTEMPT.ID.eq(Math.toIntExact(attempt.id())))
                .and(WIKI_PUBLISH_ATTEMPT.WORKSPACE_ID.eq(Math.toIntExact(attempt.workspaceId())))
                .and(WIKI_PUBLISH_ATTEMPT.RESULT.isNull())
                .execute();
        requireSingle(updated);
    }

    private UpdateSetMoreStep<WikiPublishAttemptRecord> baseCompletion(StoredWikiPublishAttempt attempt,
                                                                       StoredWikiPublishOperation operation) {
        return dsl.update(WIKI_PUBLISH_ATTEMPT)
                .set(WIKI_PUBLISH_ATTEMPT.OPERATION_ID,
                        operation == null ? null : Math.toIntExact(operation.id()))
                .set(WIKI_PUBLISH_ATTEMPT.AFTER_CONTENT_HASH,
                        operation == null ? null : operation.contentHash())
                .set(WIKI_PUBLISH_ATTEMPT.REVISION, operation == null ? null : operation.revision());
    }

    private static String idempotencyKey(StoredWikiDraft draft) {
        String identity = "wiki-publish:v1:" + draft.workspaceId() + ':' + draft.id() + ':'
                + draft.proposalId() + ':' + draft.action() + ':' + draft.targetPath() + ':'
                + (draft.expectedContentHash() == null ? "CREATE" : draft.expectedContentHash());
        return WikiContentHash.sha256(identity);
    }

    private static void requireSingle(int updated) {
        if (updated != 1) {
            throw new WikiPublishException(WikiPublishException.Reason.METADATA_FAILURE,
                    "Publish attempt audit outcome could not be persisted");
        }
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }
}
