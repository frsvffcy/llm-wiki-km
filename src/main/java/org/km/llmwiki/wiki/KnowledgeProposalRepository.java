package org.km.llmwiki.wiki;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.km.llmwiki.ai.LlmProposalAction;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.jooq.impl.DSL.cast;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.count;
import static org.km.llmwiki.persistence.jooq.generated.Tables.DOCUMENT;
import static org.km.llmwiki.persistence.jooq.generated.Tables.DOCUMENT_ANALYSIS;
import static org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_CANDIDATE;
import static org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PROPOSAL;
import static org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PROPOSAL_EVIDENCE;
import static org.km.llmwiki.persistence.jooq.generated.Tables.SOURCE_CHUNK;

/** JDBC access for auditable Proposal workflow data, isolated from future Wiki publishing. */
@Repository
public class KnowledgeProposalRepository {

    private final DSLContext dsl;

    public KnowledgeProposalRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Transactional
    public long saveDraft(KnowledgeProposalDraft draft) {
        requireConsistentReferences(draft);
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        long proposalId = insert(draft, now);
        for (long sourceChunkId : draft.evidenceSourceChunkIds()) {
            dsl.insertInto(KNOWLEDGE_PROPOSAL_EVIDENCE)
                    .columns(
                            KNOWLEDGE_PROPOSAL_EVIDENCE.KNOWLEDGE_PROPOSAL_ID,
                            KNOWLEDGE_PROPOSAL_EVIDENCE.SOURCE_CHUNK_ID
                    )
                    .values(
                            (int) proposalId,
                            (int) sourceChunkId
                    )
                    .execute();
        }
        return proposalId;
    }

    @Transactional
    public void transitionStatus(long proposalId, KnowledgeProposalStatus expected, KnowledgeProposalStatus next) {
        expected.requireTransitionTo(next);
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        int updated = dsl.update(KNOWLEDGE_PROPOSAL)
                .set(KNOWLEDGE_PROPOSAL.STATUS, next.name())
                .set(KNOWLEDGE_PROPOSAL.UPDATED_AT, now)
                .where(KNOWLEDGE_PROPOSAL.ID.eq((int) proposalId))
                .and(KNOWLEDGE_PROPOSAL.STATUS.eq(expected.name()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("Knowledge proposal was missing or did not have expected status");
        }
    }

    public Optional<KnowledgeProposal> findById(long proposalId) {
        return dsl.select(
                        KNOWLEDGE_PROPOSAL.ID,
                        KNOWLEDGE_PROPOSAL.WORKSPACE_ID,
                        KNOWLEDGE_PROPOSAL.DOCUMENT_ANALYSIS_ID,
                        KNOWLEDGE_PROPOSAL.DOCUMENT_ID,
                        KNOWLEDGE_PROPOSAL.KNOWLEDGE_CANDIDATE_ID,
                        KNOWLEDGE_PROPOSAL.ACTION,
                        KNOWLEDGE_PROPOSAL.STATUS,
                        KNOWLEDGE_PROPOSAL.MERGE_TARGET_REFERENCE,
                        KNOWLEDGE_PROPOSAL.PROVIDER,
                        KNOWLEDGE_PROPOSAL.MODEL,
                        KNOWLEDGE_PROPOSAL.PROMPT_IDENTIFIER,
                        KNOWLEDGE_PROPOSAL.PROMPT_VERSION,
                        KNOWLEDGE_PROPOSAL.CONTRACT_VERSION,
                        KNOWLEDGE_PROPOSAL.VALIDATED_PAYLOAD_JSON,
                        KNOWLEDGE_PROPOSAL.NORMALIZED_DATA_JSON
                )
                .from(KNOWLEDGE_PROPOSAL)
                .where(KNOWLEDGE_PROPOSAL.ID.eq((int) proposalId))
                .fetchOptional(r -> new KnowledgeProposal(
                        r.get(KNOWLEDGE_PROPOSAL.ID).longValue(),
                        r.get(KNOWLEDGE_PROPOSAL.WORKSPACE_ID).longValue(),
                        r.get(KNOWLEDGE_PROPOSAL.DOCUMENT_ANALYSIS_ID).longValue(),
                        r.get(KNOWLEDGE_PROPOSAL.DOCUMENT_ID).longValue(),
                        r.get(KNOWLEDGE_PROPOSAL.KNOWLEDGE_CANDIDATE_ID).longValue(),
                        LlmProposalAction.valueOf(r.get(KNOWLEDGE_PROPOSAL.ACTION)),
                        KnowledgeProposalStatus.valueOf(r.get(KNOWLEDGE_PROPOSAL.STATUS)),
                        r.get(KNOWLEDGE_PROPOSAL.MERGE_TARGET_REFERENCE),
                        r.get(KNOWLEDGE_PROPOSAL.PROVIDER),
                        r.get(KNOWLEDGE_PROPOSAL.MODEL),
                        r.get(KNOWLEDGE_PROPOSAL.PROMPT_IDENTIFIER),
                        r.get(KNOWLEDGE_PROPOSAL.PROMPT_VERSION),
                        r.get(KNOWLEDGE_PROPOSAL.CONTRACT_VERSION),
                        r.get(KNOWLEDGE_PROPOSAL.VALIDATED_PAYLOAD_JSON),
                        r.get(KNOWLEDGE_PROPOSAL.NORMALIZED_DATA_JSON),
                        evidenceIds(r.get(KNOWLEDGE_PROPOSAL.ID).longValue())
                ));
    }

    public long countReviewable(long workspaceId, KnowledgeProposalStatus status) {
        Condition condition = KNOWLEDGE_PROPOSAL.WORKSPACE_ID.eq((int) workspaceId)
                .and(DOCUMENT.WORKSPACE_ID.eq((int) workspaceId))
                .and(DOCUMENT.STATUS.notIn("DELETED", "SUPERSEDED"));
        if (status != null) {
            condition = condition.and(KNOWLEDGE_PROPOSAL.STATUS.eq(status.name()));
        }

        Integer count = dsl.select(count())
                .from(KNOWLEDGE_PROPOSAL)
                .join(DOCUMENT).on(DOCUMENT.ID.eq(KNOWLEDGE_PROPOSAL.DOCUMENT_ID))
                .join(KNOWLEDGE_CANDIDATE).on(KNOWLEDGE_CANDIDATE.ID.eq(KNOWLEDGE_PROPOSAL.KNOWLEDGE_CANDIDATE_ID))
                .where(condition)
                .fetchOne(0, Integer.class);

        return count == null ? 0 : count.longValue();
    }

    public List<KnowledgeProposalReview> findReviewable(long workspaceId, KnowledgeProposalStatus status,
                                                         long offset, int limit) {
        Condition condition = KNOWLEDGE_PROPOSAL.WORKSPACE_ID.eq((int) workspaceId)
                .and(DOCUMENT.WORKSPACE_ID.eq((int) workspaceId))
                .and(DOCUMENT.STATUS.notIn("DELETED", "SUPERSEDED"));
        if (status != null) {
            condition = condition.and(KNOWLEDGE_PROPOSAL.STATUS.eq(status.name()));
        }

        return dsl.select(
                        KNOWLEDGE_PROPOSAL.ID,
                        KNOWLEDGE_PROPOSAL.ACTION,
                        KNOWLEDGE_PROPOSAL.STATUS,
                        KNOWLEDGE_PROPOSAL.MERGE_TARGET_REFERENCE,
                        DOCUMENT.ID,
                        coalesce(DOCUMENT.ORIGINAL_FILE_NAME, DOCUMENT.FILE_NAME).as("document_file_name"),
                        DOCUMENT.SOURCE_PATH,
                        KNOWLEDGE_CANDIDATE.ID,
                        KNOWLEDGE_CANDIDATE.TITLE,
                        KNOWLEDGE_CANDIDATE.SUMMARY,
                        cast(KNOWLEDGE_CANDIDATE.CONFIDENCE, Double.class).as("confidence"),
                        KNOWLEDGE_CANDIDATE.RATIONALE
                )
                .from(KNOWLEDGE_PROPOSAL)
                .join(DOCUMENT).on(DOCUMENT.ID.eq(KNOWLEDGE_PROPOSAL.DOCUMENT_ID))
                .join(KNOWLEDGE_CANDIDATE).on(KNOWLEDGE_CANDIDATE.ID.eq(KNOWLEDGE_PROPOSAL.KNOWLEDGE_CANDIDATE_ID))
                .where(condition)
                .orderBy(KNOWLEDGE_PROPOSAL.ID.desc())
                .limit(limit)
                .offset((int) offset)
                .fetch(this::toReview);
    }

    public Optional<KnowledgeProposalReview> findReviewableById(long workspaceId, long proposalId) {
        Condition condition = KNOWLEDGE_PROPOSAL.WORKSPACE_ID.eq((int) workspaceId)
                .and(DOCUMENT.WORKSPACE_ID.eq((int) workspaceId))
                .and(DOCUMENT.STATUS.notIn("DELETED", "SUPERSEDED"))
                .and(KNOWLEDGE_PROPOSAL.ID.eq((int) proposalId));

        return dsl.select(
                        KNOWLEDGE_PROPOSAL.ID,
                        KNOWLEDGE_PROPOSAL.ACTION,
                        KNOWLEDGE_PROPOSAL.STATUS,
                        KNOWLEDGE_PROPOSAL.MERGE_TARGET_REFERENCE,
                        DOCUMENT.ID,
                        coalesce(DOCUMENT.ORIGINAL_FILE_NAME, DOCUMENT.FILE_NAME).as("document_file_name"),
                        DOCUMENT.SOURCE_PATH,
                        KNOWLEDGE_CANDIDATE.ID,
                        KNOWLEDGE_CANDIDATE.TITLE,
                        KNOWLEDGE_CANDIDATE.SUMMARY,
                        cast(KNOWLEDGE_CANDIDATE.CONFIDENCE, Double.class).as("confidence"),
                        KNOWLEDGE_CANDIDATE.RATIONALE
                )
                .from(KNOWLEDGE_PROPOSAL)
                .join(DOCUMENT).on(DOCUMENT.ID.eq(KNOWLEDGE_PROPOSAL.DOCUMENT_ID))
                .join(KNOWLEDGE_CANDIDATE).on(KNOWLEDGE_CANDIDATE.ID.eq(KNOWLEDGE_PROPOSAL.KNOWLEDGE_CANDIDATE_ID))
                .where(condition)
                .fetchOptional(this::toReview);
    }

    private long insert(KnowledgeProposalDraft draft, String now) {
        Integer id = dsl.insertInto(KNOWLEDGE_PROPOSAL)
                .columns(
                        KNOWLEDGE_PROPOSAL.WORKSPACE_ID,
                        KNOWLEDGE_PROPOSAL.DOCUMENT_ANALYSIS_ID,
                        KNOWLEDGE_PROPOSAL.DOCUMENT_ID,
                        KNOWLEDGE_PROPOSAL.KNOWLEDGE_CANDIDATE_ID,
                        KNOWLEDGE_PROPOSAL.ACTION,
                        KNOWLEDGE_PROPOSAL.STATUS,
                        KNOWLEDGE_PROPOSAL.MERGE_TARGET_REFERENCE,
                        KNOWLEDGE_PROPOSAL.PROVIDER,
                        KNOWLEDGE_PROPOSAL.MODEL,
                        KNOWLEDGE_PROPOSAL.PROMPT_IDENTIFIER,
                        KNOWLEDGE_PROPOSAL.PROMPT_VERSION,
                        KNOWLEDGE_PROPOSAL.CONTRACT_VERSION,
                        KNOWLEDGE_PROPOSAL.VALIDATED_PAYLOAD_JSON,
                        KNOWLEDGE_PROPOSAL.NORMALIZED_DATA_JSON,
                        KNOWLEDGE_PROPOSAL.CREATED_AT,
                        KNOWLEDGE_PROPOSAL.UPDATED_AT
                )
                .values(
                        (int) draft.workspaceId(),
                        (int) draft.documentAnalysisId(),
                        (int) draft.documentId(),
                        (int) draft.knowledgeCandidateId(),
                        draft.action().name(),
                        KnowledgeProposalStatus.DRAFT.name(),
                        draft.mergeTargetReference(),
                        draft.provider(),
                        draft.model(),
                        draft.promptIdentifier(),
                        draft.promptVersion(),
                        draft.contractVersion(),
                        draft.validatedPayloadJson(),
                        draft.normalizedDataJson(),
                        now,
                        now
                )
                .returningResult(KNOWLEDGE_PROPOSAL.ID)
                .fetchOne(KNOWLEDGE_PROPOSAL.ID);

        if (id == null) {
            throw new IllegalStateException("Knowledge proposal insert did not return a generated id");
        }
        return id.longValue();
    }

    private void requireConsistentReferences(KnowledgeProposalDraft draft) {
        Integer docCount = dsl.select(count())
                .from(DOCUMENT)
                .where(DOCUMENT.ID.eq((int) draft.documentId()))
                .and(DOCUMENT.WORKSPACE_ID.eq((int) draft.workspaceId()))
                .fetchOne(0, Integer.class);
        if (docCount == null || docCount != 1) {
            throw new IllegalArgumentException("document must belong to workspace");
        }

        Integer analysisCount = dsl.select(count())
                .from(DOCUMENT_ANALYSIS)
                .where(DOCUMENT_ANALYSIS.ID.eq((int) draft.documentAnalysisId()))
                .and(DOCUMENT_ANALYSIS.DOCUMENT_ID.eq((int) draft.documentId()))
                .fetchOne(0, Integer.class);
        if (analysisCount == null || analysisCount != 1) {
            throw new IllegalArgumentException("document analysis must belong to document");
        }

        Integer candidateCount = dsl.select(count())
                .from(KNOWLEDGE_CANDIDATE)
                .where(KNOWLEDGE_CANDIDATE.ID.eq((int) draft.knowledgeCandidateId()))
                .and(KNOWLEDGE_CANDIDATE.DOCUMENT_ANALYSIS_ID.eq((int) draft.documentAnalysisId()))
                .and(KNOWLEDGE_CANDIDATE.DOCUMENT_ID.eq((int) draft.documentId()))
                .fetchOne(0, Integer.class);
        if (candidateCount == null || candidateCount != 1) {
            throw new IllegalArgumentException("knowledge candidate must belong to analysis and document");
        }

        List<Integer> chunkIntIds = draft.evidenceSourceChunkIds().stream().map(Long::intValue).toList();
        Integer evidenceCount = dsl.select(count())
                .from(SOURCE_CHUNK)
                .where(SOURCE_CHUNK.DOCUMENT_ID.eq((int) draft.documentId()))
                .and(SOURCE_CHUNK.ID.in(chunkIntIds))
                .fetchOne(0, Integer.class);
        if (evidenceCount == null || evidenceCount != draft.evidenceSourceChunkIds().size()) {
            throw new IllegalArgumentException("proposal evidence must belong to document");
        }
    }

    private List<Long> evidenceIds(long proposalId) {
        return dsl.select(KNOWLEDGE_PROPOSAL_EVIDENCE.SOURCE_CHUNK_ID)
                .from(KNOWLEDGE_PROPOSAL_EVIDENCE)
                .where(KNOWLEDGE_PROPOSAL_EVIDENCE.KNOWLEDGE_PROPOSAL_ID.eq((int) proposalId))
                .orderBy(KNOWLEDGE_PROPOSAL_EVIDENCE.SOURCE_CHUNK_ID.asc())
                .fetch(r -> r.get(KNOWLEDGE_PROPOSAL_EVIDENCE.SOURCE_CHUNK_ID).longValue());
    }

    private KnowledgeProposalReview toReview(org.jooq.Record r) {
        long proposalId = r.get(KNOWLEDGE_PROPOSAL.ID).longValue();
        return new KnowledgeProposalReview(
                proposalId,
                LlmProposalAction.valueOf(r.get(KNOWLEDGE_PROPOSAL.ACTION)),
                KnowledgeProposalStatus.valueOf(r.get(KNOWLEDGE_PROPOSAL.STATUS)),
                r.get(KNOWLEDGE_PROPOSAL.MERGE_TARGET_REFERENCE),
                r.get(DOCUMENT.ID).longValue(),
                r.get("document_file_name", String.class),
                r.get(DOCUMENT.SOURCE_PATH),
                r.get(KNOWLEDGE_CANDIDATE.ID).longValue(),
                r.get(KNOWLEDGE_CANDIDATE.TITLE),
                r.get(KNOWLEDGE_CANDIDATE.SUMMARY),
                r.get("confidence", Double.class),
                r.get(KNOWLEDGE_CANDIDATE.RATIONALE),
                evidenceFor(proposalId)
        );
    }

    private List<KnowledgeProposalEvidence> evidenceFor(long proposalId) {
        return dsl.select(
                        SOURCE_CHUNK.ID,
                        SOURCE_CHUNK.CHUNK_NO,
                        SOURCE_CHUNK.PAGE_NO,
                        SOURCE_CHUNK.SECTION,
                        SOURCE_CHUNK.HEADING_PATH,
                        SOURCE_CHUNK.CONTENT
                )
                .from(KNOWLEDGE_PROPOSAL_EVIDENCE)
                .join(SOURCE_CHUNK).on(SOURCE_CHUNK.ID.eq(KNOWLEDGE_PROPOSAL_EVIDENCE.SOURCE_CHUNK_ID))
                .where(KNOWLEDGE_PROPOSAL_EVIDENCE.KNOWLEDGE_PROPOSAL_ID.eq((int) proposalId))
                .orderBy(SOURCE_CHUNK.CHUNK_NO.asc())
                .fetch(r -> new KnowledgeProposalEvidence(
                        r.get(SOURCE_CHUNK.ID).longValue(),
                        r.get(SOURCE_CHUNK.CHUNK_NO),
                        r.get(SOURCE_CHUNK.PAGE_NO),
                        r.get(SOURCE_CHUNK.SECTION),
                        r.get(SOURCE_CHUNK.HEADING_PATH),
                        r.get(SOURCE_CHUNK.CONTENT)
                ));
    }
}
