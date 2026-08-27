package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/** JDBC access for auditable Proposal workflow data, isolated from future Wiki publishing. */
@Repository
public class KnowledgeProposalRepository {

    private final JdbcClient jdbcClient;

    public KnowledgeProposalRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public long saveDraft(KnowledgeProposalDraft draft) {
        requireConsistentReferences(draft);
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        long proposalId = insert(draft, now);
        for (long sourceChunkId : draft.evidenceSourceChunkIds()) {
            jdbcClient.sql("""
                            INSERT INTO knowledge_proposal_evidence (knowledge_proposal_id, source_chunk_id)
                            VALUES (:proposalId, :sourceChunkId)
                            """)
                    .param("proposalId", proposalId).param("sourceChunkId", sourceChunkId).update();
        }
        return proposalId;
    }

    @Transactional
    public void transitionStatus(long proposalId, KnowledgeProposalStatus expected, KnowledgeProposalStatus next) {
        expected.requireTransitionTo(next);
        int updated = jdbcClient.sql("""
                        UPDATE knowledge_proposal
                        SET status = :next, updated_at = :now
                        WHERE id = :proposalId AND status = :expected
                        """)
                .param("next", next.name()).param("now", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .param("proposalId", proposalId).param("expected", expected.name()).update();
        if (updated != 1) {
            throw new IllegalStateException("Knowledge proposal was missing or did not have expected status");
        }
    }

    public Optional<KnowledgeProposal> findById(long proposalId) {
        return jdbcClient.sql("""
                        SELECT id, workspace_id, document_analysis_id, document_id, knowledge_candidate_id, action,
                               status, merge_target_reference, provider, model, prompt_identifier, prompt_version,
                               contract_version, validated_payload_json, normalized_data_json
                        FROM knowledge_proposal WHERE id = :proposalId
                        """).param("proposalId", proposalId)
                .query((resultSet, rowNum) -> new KnowledgeProposal(
                        resultSet.getLong("id"), resultSet.getLong("workspace_id"),
                        resultSet.getLong("document_analysis_id"), resultSet.getLong("document_id"),
                        resultSet.getLong("knowledge_candidate_id"),
                        LlmProposalAction.valueOf(resultSet.getString("action")),
                        KnowledgeProposalStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("merge_target_reference"), resultSet.getString("provider"),
                        resultSet.getString("model"), resultSet.getString("prompt_identifier"),
                        resultSet.getString("prompt_version"), resultSet.getString("contract_version"),
                        resultSet.getString("validated_payload_json"), resultSet.getString("normalized_data_json"),
                        evidenceIds(resultSet.getLong("id"))))
                .optional();
    }

    private long insert(KnowledgeProposalDraft draft, String now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO knowledge_proposal (
                            workspace_id, document_analysis_id, document_id, knowledge_candidate_id, action, status,
                            merge_target_reference, provider, model, prompt_identifier, prompt_version,
                            contract_version, validated_payload_json, normalized_data_json, created_at, updated_at)
                        VALUES (
                            :workspaceId, :documentAnalysisId, :documentId, :knowledgeCandidateId, :action, :status,
                            :mergeTargetReference, :provider, :model, :promptIdentifier, :promptVersion,
                            :contractVersion, :validatedPayloadJson, :normalizedDataJson, :now, :now)
                        """)
                .paramSource(new MapSqlParameterSource()
                        .addValue("workspaceId", draft.workspaceId())
                        .addValue("documentAnalysisId", draft.documentAnalysisId())
                        .addValue("documentId", draft.documentId())
                        .addValue("knowledgeCandidateId", draft.knowledgeCandidateId())
                        .addValue("action", draft.action().name()).addValue("status", KnowledgeProposalStatus.DRAFT.name())
                        .addValue("mergeTargetReference", draft.mergeTargetReference()).addValue("provider", draft.provider())
                        .addValue("model", draft.model()).addValue("promptIdentifier", draft.promptIdentifier())
                        .addValue("promptVersion", draft.promptVersion()).addValue("contractVersion", draft.contractVersion())
                        .addValue("validatedPayloadJson", draft.validatedPayloadJson())
                        .addValue("normalizedDataJson", draft.normalizedDataJson()).addValue("now", now))
                .update(keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("Knowledge proposal insert did not return a generated id");
        }
        return id.longValue();
    }

    private void requireConsistentReferences(KnowledgeProposalDraft draft) {
        requireCount("SELECT COUNT(*) FROM document WHERE id = :documentId AND workspace_id = :workspaceId", draft,
                "document must belong to workspace");
        requireCount("SELECT COUNT(*) FROM document_analysis WHERE id = :documentAnalysisId AND document_id = :documentId",
                draft, "document analysis must belong to document");
        requireCount("""
                        SELECT COUNT(*) FROM knowledge_candidate
                        WHERE id = :knowledgeCandidateId AND document_analysis_id = :documentAnalysisId
                          AND document_id = :documentId
                        """, draft, "knowledge candidate must belong to analysis and document");
        Integer evidenceCount = jdbcClient.sql("""
                        SELECT COUNT(*) FROM source_chunk
                        WHERE document_id = :documentId AND id IN (:sourceChunkIds)
                        """).param("documentId", draft.documentId()).param("sourceChunkIds", draft.evidenceSourceChunkIds())
                .query(Integer.class).single();
        if (evidenceCount == null || evidenceCount != draft.evidenceSourceChunkIds().size()) {
            throw new IllegalArgumentException("proposal evidence must belong to document");
        }
    }

    private void requireCount(String sql, KnowledgeProposalDraft draft, String message) {
        Integer count = jdbcClient.sql(sql).paramSource(new MapSqlParameterSource()
                        .addValue("workspaceId", draft.workspaceId())
                        .addValue("documentAnalysisId", draft.documentAnalysisId()).addValue("documentId", draft.documentId())
                        .addValue("knowledgeCandidateId", draft.knowledgeCandidateId()))
                .query(Integer.class).single();
        if (count == null || count != 1) {
            throw new IllegalArgumentException(message);
        }
    }

    private List<Long> evidenceIds(long proposalId) {
        return jdbcClient.sql("""
                        SELECT source_chunk_id FROM knowledge_proposal_evidence
                        WHERE knowledge_proposal_id = :proposalId ORDER BY source_chunk_id
                        """).param("proposalId", proposalId).query(Long.class).list();
    }
}
