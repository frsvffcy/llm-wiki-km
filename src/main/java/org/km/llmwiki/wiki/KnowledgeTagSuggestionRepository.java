package org.km.llmwiki.wiki;

import org.jooq.DSLContext;
import org.km.llmwiki.ai.KnowledgeCandidateType;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static org.km.llmwiki.persistence.jooq.generated.Tables.DOCUMENT;
import static org.km.llmwiki.persistence.jooq.generated.Tables.DOCUMENT_ANALYSIS;
import static org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_CANDIDATE;
import static org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PROPOSAL;

/**
 * #569 suggestion projection 的唯讀查詢邊界：所有讀取皆 workspace-scoped；
 * 不寫入任何 table（suggestion 是 ephemeral，不持久化、不影響 retrieval projections）。
 */
@Repository
public class KnowledgeTagSuggestionRepository {

    private final DSLContext dsl;

    public KnowledgeTagSuggestionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Workspace 內可見的文件錨點（DELETED／SUPERSEDED 不可見；跨 workspace 回空→404）。 */
    public Optional<DocumentAnchor> findDocumentAnchor(long workspaceId, long documentId) {
        return dsl.select(DOCUMENT.ID, DOCUMENT.UPDATED_AT, DOCUMENT.STATUS)
                .from(DOCUMENT)
                .where(DOCUMENT.ID.eq((int) documentId))
                .and(DOCUMENT.WORKSPACE_ID.eq((int) workspaceId))
                .and(DOCUMENT.STATUS.notIn("DELETED", "SUPERSEDED"))
                .fetchOptional(r -> new DocumentAnchor(
                        r.get(DOCUMENT.ID).longValue(),
                        r.get(DOCUMENT.UPDATED_AT),
                        r.get(DOCUMENT.STATUS)));
    }

    /** 該文件最新一次成功的分析（id 最大者；無成功分析回空→NO_ANALYSIS）。 */
    public Optional<AnalysisAnchor> findLatestSucceededAnalysis(long documentId) {
        return dsl.select(DOCUMENT_ANALYSIS.ID, DOCUMENT_ANALYSIS.CREATED_AT)
                .from(DOCUMENT_ANALYSIS)
                .where(DOCUMENT_ANALYSIS.DOCUMENT_ID.eq((int) documentId))
                .and(DOCUMENT_ANALYSIS.STATUS.eq("SUCCEEDED"))
                .orderBy(DOCUMENT_ANALYSIS.ID.desc())
                .limit(1)
                .fetchOptional(r -> new AnalysisAnchor(
                        r.get(DOCUMENT_ANALYSIS.ID).longValue(),
                        r.get(DOCUMENT_ANALYSIS.CREATED_AT)));
    }

    /** 指定分析的 candidates（candidate_no 升冪；deterministic ordering）。 */
    public List<CandidateRow> findCandidates(long analysisId) {
        return dsl.select(
                        KNOWLEDGE_CANDIDATE.ID,
                        KNOWLEDGE_CANDIDATE.CANDIDATE_NO,
                        KNOWLEDGE_CANDIDATE.TITLE,
                        KNOWLEDGE_CANDIDATE.CANDIDATE_TYPE,
                        KNOWLEDGE_CANDIDATE.SUMMARY,
                        KNOWLEDGE_CANDIDATE.CONFIDENCE,
                        KNOWLEDGE_CANDIDATE.RATIONALE)
                .from(KNOWLEDGE_CANDIDATE)
                .where(KNOWLEDGE_CANDIDATE.DOCUMENT_ANALYSIS_ID.eq((int) analysisId))
                .orderBy(KNOWLEDGE_CANDIDATE.CANDIDATE_NO.asc())
                .fetch(r -> new CandidateRow(
                        r.get(KNOWLEDGE_CANDIDATE.ID).longValue(),
                        r.get(KNOWLEDGE_CANDIDATE.CANDIDATE_NO),
                        r.get(KNOWLEDGE_CANDIDATE.TITLE),
                        KnowledgeCandidateType.valueOf(r.get(KNOWLEDGE_CANDIDATE.CANDIDATE_TYPE)),
                        r.get(KNOWLEDGE_CANDIDATE.SUMMARY),
                        r.get(KNOWLEDGE_CANDIDATE.CONFIDENCE) == null
                                ? 0.0 : r.get(KNOWLEDGE_CANDIDATE.CONFIDENCE).doubleValue(),
                        r.get(KNOWLEDGE_CANDIDATE.RATIONALE)));
    }

    /**
     * 該文件 analysis 來源提案的最新 tags 線索（id 降冪；service 取每 candidate 最新一筆
     * 非 REJECTED 者；REPAIR／ASK 不屬 analysis 建議鏈，此處只讀 DOCUMENT_ANALYSIS）。
     */
    public List<ProposalTagRow> findAnalysisProposalTags(long workspaceId, long documentId) {
        return dsl.select(
                        KNOWLEDGE_PROPOSAL.ID,
                        KNOWLEDGE_PROPOSAL.KNOWLEDGE_CANDIDATE_ID,
                        KNOWLEDGE_PROPOSAL.STATUS,
                        KNOWLEDGE_PROPOSAL.NORMALIZED_DATA_JSON)
                .from(KNOWLEDGE_PROPOSAL)
                .where(KNOWLEDGE_PROPOSAL.WORKSPACE_ID.eq((int) workspaceId))
                .and(KNOWLEDGE_PROPOSAL.DOCUMENT_ID.eq((int) documentId))
                .and(KNOWLEDGE_PROPOSAL.SOURCE_KIND.eq("DOCUMENT_ANALYSIS"))
                .orderBy(KNOWLEDGE_PROPOSAL.ID.desc())
                .fetch(r -> new ProposalTagRow(
                        r.get(KNOWLEDGE_PROPOSAL.ID).longValue(),
                        r.get(KNOWLEDGE_PROPOSAL.KNOWLEDGE_CANDIDATE_ID).longValue(),
                        r.get(KNOWLEDGE_PROPOSAL.STATUS),
                        r.get(KNOWLEDGE_PROPOSAL.NORMALIZED_DATA_JSON)));
    }

    public record DocumentAnchor(long documentId, String updatedAt, String status) {
    }

    public record AnalysisAnchor(long analysisId, String createdAt) {
    }

    public record CandidateRow(long candidateId, int candidateNo, String title, KnowledgeCandidateType type,
                               String summary, double confidence, String rationale) {
    }

    public record ProposalTagRow(long proposalId, long candidateId, String status, String normalizedDataJson) {
    }
}
