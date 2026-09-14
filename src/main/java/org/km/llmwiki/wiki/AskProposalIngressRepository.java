package org.km.llmwiki.wiki;

import org.jooq.DSLContext;
import org.km.llmwiki.ai.LlmProposalAction;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PROPOSAL;

/**
 * Persistence for the governed Ask -> Proposal ingress (#374). Ask-sourced proposals
 * carry no document-analysis chain; the dedup hash gives repeated submissions an
 * explicit idempotency contract enforced by a partial unique index.
 */
@Repository
public class AskProposalIngressRepository {

    private final DSLContext dsl;

    public AskProposalIngressRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<KnowledgeProposalReview> findBySourceDedupHash(long workspaceId, String dedupHash) {
        return dsl.selectFrom(KNOWLEDGE_PROPOSAL)
                .where(KNOWLEDGE_PROPOSAL.WORKSPACE_ID.eq((int) workspaceId))
                .and(KNOWLEDGE_PROPOSAL.SOURCE_KIND.eq("ASK"))
                .and(KNOWLEDGE_PROPOSAL.SOURCE_DEDUP_HASH.eq(dedupHash))
                .fetchOptional(record -> new KnowledgeProposalReview(
                        record.getId().longValue(),
                        LlmProposalAction.valueOf(record.getAction()),
                        KnowledgeProposalStatus.valueOf(record.getStatus()),
                        record.getMergeTargetReference(),
                        null, null, null, null,
                        record.getAskQuestion(),
                        record.getAskAnswerText() == null ? null
                                : record.getAskAnswerText().substring(0,
                                        Math.min(400, record.getAskAnswerText().length())),
                        null,
                        "Governed proposal created from an explicit Ask hand-off",
                        List.of()));
    }

    /**
     * Inserts one ASK-sourced proposal. Duplicate submissions lose the race on the
     * partial unique index and surface as an integrity violation the caller translates
     * into the dedup contract.
     */
    public long insertAskProposal(long workspaceId, CreateAskProposalRequest request,
                                  String normalizedDataJson, String citationsJson,
                                  List<Long> evidenceSourceChunkIds, String dedupHash) {
        try {
            long proposalId = dsl.insertInto(KNOWLEDGE_PROPOSAL)
                    .set(KNOWLEDGE_PROPOSAL.WORKSPACE_ID, (int) workspaceId)
                    .setNull(KNOWLEDGE_PROPOSAL.DOCUMENT_ANALYSIS_ID)
                    .setNull(KNOWLEDGE_PROPOSAL.DOCUMENT_ID)
                    .setNull(KNOWLEDGE_PROPOSAL.KNOWLEDGE_CANDIDATE_ID)
                    .set(KNOWLEDGE_PROPOSAL.ACTION, "CREATE")
                    .set(KNOWLEDGE_PROPOSAL.STATUS, "REVIEW")
                    .set(KNOWLEDGE_PROPOSAL.PROVIDER, request.provider())
                    .set(KNOWLEDGE_PROPOSAL.MODEL, request.model())
                    .set(KNOWLEDGE_PROPOSAL.PROMPT_IDENTIFIER, "grounded-answer")
                    .set(KNOWLEDGE_PROPOSAL.PROMPT_VERSION, "v2")
                    .set(KNOWLEDGE_PROPOSAL.CONTRACT_VERSION, "v2")
                    .set(KNOWLEDGE_PROPOSAL.NORMALIZED_DATA_JSON, normalizedDataJson)
                    .set(KNOWLEDGE_PROPOSAL.SOURCE_KIND, "ASK")
                    .set(KNOWLEDGE_PROPOSAL.ASK_QUESTION, request.question())
                    .set(KNOWLEDGE_PROPOSAL.ASK_ANSWER_TEXT, request.answerText())
                    .set(KNOWLEDGE_PROPOSAL.ASK_CITATIONS_JSON, citationsJson)
                    .set(KNOWLEDGE_PROPOSAL.SOURCE_DEDUP_HASH, dedupHash)
                    .set(KNOWLEDGE_PROPOSAL.CREATED_AT, java.time.Instant.now().toString())
                    .set(KNOWLEDGE_PROPOSAL.UPDATED_AT, java.time.Instant.now().toString())
                    .returningResult(KNOWLEDGE_PROPOSAL.ID)
                    .fetchOne()
                    .getValue(KNOWLEDGE_PROPOSAL.ID).longValue();
            for (Long chunkId : evidenceSourceChunkIds) {
                dsl.insertInto(org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PROPOSAL_EVIDENCE)
                        .set(org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PROPOSAL_EVIDENCE.KNOWLEDGE_PROPOSAL_ID,
                                (int) proposalId)
                        .set(org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PROPOSAL_EVIDENCE.SOURCE_CHUNK_ID,
                                chunkId.intValue())
                        .execute();
            }
            return proposalId;
        } catch (IntegrityConstraintViolationException violation) {
            // Only the dedup index translates into the idempotency contract; any other
            // constraint violation is a bug and must surface with its real cause.
            // SQLite reports unique violations by constrained columns, not index names,
            // and the ASK/REPAIR partial indexes are kind-disjoint — so a UNIQUE failure
            // mentioning source_dedup_hash on an ASK insert can only be this index.
            if (violation.getMessage() != null
                    && violation.getMessage().contains("UNIQUE constraint failed")
                    && violation.getMessage().contains("source_dedup_hash")) {
                throw new DuplicateAskProposalException(violation.getMessage());
            }
            throw new IllegalStateException(
                    "ask proposal insert violated an unexpected constraint", violation);
        }
    }
}
