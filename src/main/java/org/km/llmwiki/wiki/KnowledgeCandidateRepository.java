package org.km.llmwiki.wiki;

import org.jooq.DSLContext;
import org.km.llmwiki.ai.KnowledgeCandidate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_CANDIDATE;
import static org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_CANDIDATE_EVIDENCE;

/** Persists reviewable candidates separately from any future Wiki proposal or page. */
@Repository
public class KnowledgeCandidateRepository {

    private final DSLContext dsl;

    public KnowledgeCandidateRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void saveAll(long documentAnalysisId, long documentId, List<KnowledgeCandidate> candidates) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        for (int candidateNo = 0; candidateNo < candidates.size(); candidateNo++) {
            KnowledgeCandidate candidate = candidates.get(candidateNo);
            long candidateId = insert(documentAnalysisId, documentId, candidateNo + 1, candidate, now);
            for (long sourceChunkId : candidate.evidenceSourceChunkIds()) {
                dsl.insertInto(KNOWLEDGE_CANDIDATE_EVIDENCE)
                        .columns(
                                KNOWLEDGE_CANDIDATE_EVIDENCE.KNOWLEDGE_CANDIDATE_ID,
                                KNOWLEDGE_CANDIDATE_EVIDENCE.SOURCE_CHUNK_ID
                        )
                        .values(
                                (int) candidateId,
                                (int) sourceChunkId
                        )
                        .execute();
            }
        }
    }

    private long insert(long documentAnalysisId, long documentId, int candidateNo, KnowledgeCandidate candidate,
                        String now) {
        Integer id = dsl.insertInto(KNOWLEDGE_CANDIDATE)
                .columns(
                        KNOWLEDGE_CANDIDATE.DOCUMENT_ANALYSIS_ID,
                        KNOWLEDGE_CANDIDATE.DOCUMENT_ID,
                        KNOWLEDGE_CANDIDATE.CANDIDATE_NO,
                        KNOWLEDGE_CANDIDATE.TITLE,
                        KNOWLEDGE_CANDIDATE.CANDIDATE_TYPE,
                        KNOWLEDGE_CANDIDATE.SUMMARY,
                        KNOWLEDGE_CANDIDATE.CONFIDENCE,
                        KNOWLEDGE_CANDIDATE.RATIONALE,
                        KNOWLEDGE_CANDIDATE.CREATED_AT,
                        KNOWLEDGE_CANDIDATE.UPDATED_AT
                )
                .values(
                        (int) documentAnalysisId,
                        (int) documentId,
                        candidateNo,
                        candidate.title(),
                        candidate.type().name(),
                        candidate.summary(),
                        (float) candidate.confidence(),
                        candidate.rationale(),
                        now,
                        now
                )
                .returningResult(KNOWLEDGE_CANDIDATE.ID)
                .fetchOne(KNOWLEDGE_CANDIDATE.ID);

        if (id == null) {
            throw new IllegalStateException("Knowledge candidate insert did not return a generated id");
        }
        return id.longValue();
    }
}
