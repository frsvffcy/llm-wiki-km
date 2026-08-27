package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.KnowledgeCandidate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Persists reviewable candidates separately from any future Wiki proposal or page. */
@Repository
public class KnowledgeCandidateRepository {

    private final JdbcClient jdbcClient;

    public KnowledgeCandidateRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void saveAll(long documentAnalysisId, long documentId, List<KnowledgeCandidate> candidates) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        for (int candidateNo = 0; candidateNo < candidates.size(); candidateNo++) {
            KnowledgeCandidate candidate = candidates.get(candidateNo);
            long candidateId = insert(documentAnalysisId, documentId, candidateNo + 1, candidate, now);
            for (long sourceChunkId : candidate.evidenceSourceChunkIds()) {
                jdbcClient.sql("""
                                INSERT INTO knowledge_candidate_evidence (knowledge_candidate_id, source_chunk_id)
                                VALUES (:candidateId, :sourceChunkId)
                                """)
                        .param("candidateId", candidateId).param("sourceChunkId", sourceChunkId).update();
            }
        }
    }

    private long insert(long documentAnalysisId, long documentId, int candidateNo, KnowledgeCandidate candidate,
                        String now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO knowledge_candidate (
                            document_analysis_id, document_id, candidate_no, title, candidate_type,
                            summary, confidence, rationale, created_at, updated_at)
                        VALUES (
                            :documentAnalysisId, :documentId, :candidateNo, :title, :candidateType,
                            :summary, :confidence, :rationale, :now, :now)
                        """)
                .paramSource(new MapSqlParameterSource()
                        .addValue("documentAnalysisId", documentAnalysisId).addValue("documentId", documentId)
                        .addValue("candidateNo", candidateNo).addValue("title", candidate.title())
                        .addValue("candidateType", candidate.type().name()).addValue("summary", candidate.summary())
                        .addValue("confidence", candidate.confidence()).addValue("rationale", candidate.rationale())
                        .addValue("now", now))
                .update(keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("Knowledge candidate insert did not return a generated id");
        }
        return id.longValue();
    }
}
