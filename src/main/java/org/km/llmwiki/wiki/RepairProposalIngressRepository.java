package org.km.llmwiki.wiki;

import org.jooq.DSLContext;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PAGE;
import static org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PROPOSAL;
import static org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PROPOSAL_EVIDENCE;

/**
 * Persistence for the governed repair ingress (#384). Repair-sourced proposals carry no
 * document-analysis chain; the dedup hash gives repeated submissions an explicit
 * idempotency contract enforced by a partial unique index (V31).
 */
@Repository
public class RepairProposalIngressRepository {

    private final DSLContext dsl;

    public RepairProposalIngressRepository(DSLContext dsl) {
        this.dsl = dsl;
    }
    public Long findPageProposalId(long workspaceId, long knowledgePageId) {
        Integer proposalId = dsl.select(KNOWLEDGE_PAGE.PROPOSAL_ID)
                .from(KNOWLEDGE_PAGE)
                .where(KNOWLEDGE_PAGE.ID.eq((int) knowledgePageId))
                .and(KNOWLEDGE_PAGE.WORKSPACE_ID.eq((int) workspaceId))
                .fetchOne(0, Integer.class);
        return proposalId == null ? null : proposalId.longValue();
    }

    public Optional<String> findContractVersion(long workspaceId, long proposalId) {
        return dsl.select(KNOWLEDGE_PROPOSAL.CONTRACT_VERSION)
                .from(KNOWLEDGE_PROPOSAL)
                .where(KNOWLEDGE_PROPOSAL.ID.eq((int) proposalId))
                .and(KNOWLEDGE_PROPOSAL.WORKSPACE_ID.eq((int) workspaceId))
                .fetchOptional(KNOWLEDGE_PROPOSAL.CONTRACT_VERSION);
    }

    public Optional<KnowledgeProposalReview> findByRepairDedupHash(long workspaceId, String dedupHash) {
        return dsl.selectFrom(KNOWLEDGE_PROPOSAL)
                .where(KNOWLEDGE_PROPOSAL.WORKSPACE_ID.eq((int) workspaceId))
                .and(KNOWLEDGE_PROPOSAL.SOURCE_KIND.eq("REPAIR"))
                .and(KNOWLEDGE_PROPOSAL.SOURCE_DEDUP_HASH.eq(dedupHash))
                .and(KNOWLEDGE_PROPOSAL.STATUS.ne("REJECTED"))
                .fetchOptional(record -> new KnowledgeProposalReview(
                        record.getId().longValue(),
                        org.km.llmwiki.ai.LlmProposalAction.valueOf(record.getAction()),
                        KnowledgeProposalStatus.valueOf(record.getStatus()),
                        record.getMergeTargetReference(),
                        null, null, null, null,
                        "Vault repair: " + record.getMergeTargetReference(),
                        "Governed proposal created from an explicit repair action",
                        null,
                        "Governed repair created from an explicit human repair action",
                        List.of()));
    }

    /**
     * Inserts one REPAIR-sourced proposal. Duplicate submissions lose the race on the
     * partial unique index and surface as an integrity violation the caller translates
     * into the dedup contract.
     */
    public long insertRepairProposal(long workspaceId, RepairPlan plan, String contractVersion) {
        try {
            long proposalId = dsl.insertInto(KNOWLEDGE_PROPOSAL)
                    .set(KNOWLEDGE_PROPOSAL.WORKSPACE_ID, (int) workspaceId)
                    .setNull(KNOWLEDGE_PROPOSAL.DOCUMENT_ANALYSIS_ID)
                    .setNull(KNOWLEDGE_PROPOSAL.DOCUMENT_ID)
                    .setNull(KNOWLEDGE_PROPOSAL.KNOWLEDGE_CANDIDATE_ID)
                    .set(KNOWLEDGE_PROPOSAL.ACTION, "MERGE")
                    .set(KNOWLEDGE_PROPOSAL.STATUS, "REVIEW")
                    .set(KNOWLEDGE_PROPOSAL.MERGE_TARGET_REFERENCE, plan.mergeTargetReference())
                    .set(KNOWLEDGE_PROPOSAL.PROVIDER, VaultRepairService.REPAIR_PROVIDER)
                    .set(KNOWLEDGE_PROPOSAL.MODEL, VaultRepairService.REPAIR_MODEL)
                    .set(KNOWLEDGE_PROPOSAL.PROMPT_IDENTIFIER, VaultRepairService.REPAIR_PROMPT_IDENTIFIER)
                    .set(KNOWLEDGE_PROPOSAL.PROMPT_VERSION, VaultRepairService.REPAIR_PROMPT_VERSION)
                    .set(KNOWLEDGE_PROPOSAL.CONTRACT_VERSION, contractVersion)
                    .set(KNOWLEDGE_PROPOSAL.VALIDATED_PAYLOAD_JSON, plan.validatedPayloadJson())
                    .set(KNOWLEDGE_PROPOSAL.NORMALIZED_DATA_JSON, plan.normalizedDataJson())
                    .set(KNOWLEDGE_PROPOSAL.SOURCE_KIND, "REPAIR")
                    .set(KNOWLEDGE_PROPOSAL.SOURCE_DEDUP_HASH, plan.dedupHash())
                    .set(KNOWLEDGE_PROPOSAL.CREATED_AT, java.time.Instant.now().toString())
                    .set(KNOWLEDGE_PROPOSAL.UPDATED_AT, java.time.Instant.now().toString())
                    .returningResult(KNOWLEDGE_PROPOSAL.ID)
                    .fetchOne()
                    .getValue(KNOWLEDGE_PROPOSAL.ID).longValue();
            for (Long chunkId : plan.evidenceChunkIds()) {
                dsl.insertInto(KNOWLEDGE_PROPOSAL_EVIDENCE)
                        .set(KNOWLEDGE_PROPOSAL_EVIDENCE.KNOWLEDGE_PROPOSAL_ID, (int) proposalId)
                        .set(KNOWLEDGE_PROPOSAL_EVIDENCE.SOURCE_CHUNK_ID, chunkId.intValue())
                        .execute();
            }
            return proposalId;
        } catch (IntegrityConstraintViolationException violation) {
            // Only the dedup index translates into the idempotency contract; any other
            // constraint violation is a bug and must surface with its real cause.
            // SQLite reports unique violations by constrained columns, not index names,
            // and the ASK/REPAIR partial indexes are kind-disjoint — so a UNIQUE failure
            // mentioning source_dedup_hash on a REPAIR insert can only be this index.
            if (violation.getMessage() != null
                    && violation.getMessage().contains("UNIQUE constraint failed")
                    && violation.getMessage().contains("source_dedup_hash")) {
                throw new DuplicateRepairProposalException(violation.getMessage());
            }
            throw new IllegalStateException(
                    "repair proposal insert violated an unexpected constraint", violation);
        }
    }
}
