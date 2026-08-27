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

    public long countReviewable(long workspaceId, KnowledgeProposalStatus status) {
        StringBuilder sql = reviewableSelect("COUNT(*)");
        if (status != null) {
            sql.append(" AND knowledge_proposal.status = :status");
        }
        var statement = jdbcClient.sql(sql.toString()).param("workspaceId", workspaceId);
        if (status != null) {
            statement = statement.param("status", status.name());
        }
        Long count = statement.query(Long.class).single();
        return count == null ? 0 : count;
    }

    public List<KnowledgeProposalReview> findReviewable(long workspaceId, KnowledgeProposalStatus status,
                                                         long offset, int limit) {
        StringBuilder sql = reviewableSelect(reviewColumns());
        if (status != null) {
            sql.append(" AND knowledge_proposal.status = :status");
        }
        sql.append(" ORDER BY knowledge_proposal.id DESC LIMIT :limit OFFSET :offset");
        var statement = jdbcClient.sql(sql.toString()).param("workspaceId", workspaceId)
                .param("limit", limit).param("offset", offset);
        if (status != null) {
            statement = statement.param("status", status.name());
        }
        return statement.query((resultSet, rowNum) -> review(resultSet)).list();
    }

    public Optional<KnowledgeProposalReview> findReviewableById(long workspaceId, long proposalId) {
        String sql = reviewableSelect(reviewColumns()) + " AND knowledge_proposal.id = :proposalId";
        return jdbcClient.sql(sql).param("workspaceId", workspaceId).param("proposalId", proposalId)
                .query((resultSet, rowNum) -> review(resultSet)).optional();
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

    private KnowledgeProposalReview review(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        long proposalId = resultSet.getLong("proposal_id");
        return new KnowledgeProposalReview(proposalId, LlmProposalAction.valueOf(resultSet.getString("action")),
                KnowledgeProposalStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("merge_target_reference"), resultSet.getLong("document_id"),
                resultSet.getString("document_file_name"), resultSet.getString("source_path"),
                resultSet.getLong("candidate_id"), resultSet.getString("title"), resultSet.getString("summary"),
                resultSet.getDouble("confidence"), resultSet.getString("rationale"), evidenceFor(proposalId));
    }

    private List<KnowledgeProposalEvidence> evidenceFor(long proposalId) {
        return jdbcClient.sql("""
                        SELECT source_chunk.id, source_chunk.chunk_no, source_chunk.page_no, source_chunk.section,
                               source_chunk.heading_path, source_chunk.content
                        FROM knowledge_proposal_evidence
                        JOIN source_chunk ON source_chunk.id = knowledge_proposal_evidence.source_chunk_id
                        WHERE knowledge_proposal_evidence.knowledge_proposal_id = :proposalId
                        ORDER BY source_chunk.chunk_no
                        """).param("proposalId", proposalId)
                .query((resultSet, rowNum) -> new KnowledgeProposalEvidence(resultSet.getLong("id"),
                        resultSet.getInt("chunk_no"), nullableInt(resultSet, "page_no"),
                        resultSet.getString("section"), resultSet.getString("heading_path"),
                        resultSet.getString("content")))
                .list();
    }

    private static Integer nullableInt(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static StringBuilder reviewableSelect(String columns) {
        return new StringBuilder("SELECT ").append(columns).append("""
                 FROM knowledge_proposal
                 JOIN document ON document.id = knowledge_proposal.document_id
                 JOIN knowledge_candidate ON knowledge_candidate.id = knowledge_proposal.knowledge_candidate_id
                 WHERE knowledge_proposal.workspace_id = :workspaceId
                   AND document.workspace_id = :workspaceId
                   AND document.status NOT IN ('DELETED', 'SUPERSEDED')
                """);
    }

    private static String reviewColumns() {
        return """
                knowledge_proposal.id AS proposal_id, knowledge_proposal.action, knowledge_proposal.status,
                knowledge_proposal.merge_target_reference, document.id AS document_id,
                COALESCE(document.original_file_name, document.file_name) AS document_file_name, document.source_path,
                knowledge_candidate.id AS candidate_id, knowledge_candidate.title, knowledge_candidate.summary,
                knowledge_candidate.confidence, knowledge_candidate.rationale
                """;
    }
}
