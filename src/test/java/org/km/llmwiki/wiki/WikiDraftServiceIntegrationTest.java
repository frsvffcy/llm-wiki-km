package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.KnowledgeCandidateType;
import org.km.llmwiki.ai.LlmProposalAction;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "app.persistence.sqlite.path=target/test-data/wiki-drafts-${random.uuid}/knowledge.db"
})
class WikiDraftServiceIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private KnowledgeProposalRepository proposalRepository;

    @Autowired
    private WikiDraftService service;

    @Test
    void convertsOnlyApprovedProposalThroughJooqProjectionWithoutPersistenceOrFilesystemWrite() {
        Fixture fixture = createFixture(KnowledgeCandidateType.FACT);
        long proposalId = saveProposal(fixture, LlmProposalAction.CREATE, null, """
                {
                  "title":"/tmp/Architecture Facts",
                  "pageType":"TECHNOLOGY",
                  "summary":"Approved architecture facts",
                  "tags":["JOOQ","architecture"],
                  "sourceChunkIds":[%d,%d],
                  "sections":[{"heading":"Facts","content":"Validated facts."}],
                  "wikilinks":[{"targetTitle":"jOOQ","label":"jOOQ"}]
                }
                """.formatted(fixture.secondChunkId(), fixture.firstChunkId()));
        proposalRepository.transitionStatus(proposalId, KnowledgeProposalStatus.DRAFT,
                KnowledgeProposalStatus.REVIEW);
        proposalRepository.transitionStatus(proposalId, KnowledgeProposalStatus.REVIEW,
                KnowledgeProposalStatus.APPROVED);
        int proposalsBefore = count("knowledge_proposal");

        WikiDraft first = service.convertApproved(proposalId);
        WikiDraft second = service.convertApproved(proposalId);

        assertThat(first).isEqualTo(second);
        assertThat(first.action()).isEqualTo(LlmProposalAction.CREATE);
        assertThat(first.pageType()).isEqualTo(WikiPageType.TECHNOLOGY);
        assertThat(first.target().kind()).isEqualTo(WikiDraftTarget.Kind.CREATE_NEW);
        assertThat(first.target().logicalRelativePath())
                .isEqualTo("vault/technologies/tmparchitecture-facts.md");
        assertThat(first.frontmatter().sourceDocumentIds()).containsExactly(fixture.documentId());
        assertThat(first.sourceChunkIds()).containsExactly(fixture.firstChunkId(), fixture.secondChunkId());
        assertThat(first.evidence()).extracting(WikiDraftEvidence::excerpt)
                .containsExactly("Source content 1", "Source content 2");
        assertThat(count("knowledge_proposal")).isEqualTo(proposalsBefore);
        assertThat(count("knowledge_proposal_evidence")).isEqualTo(2);
    }

    @Test
    void rejectsProposalThatHasNotReachedApprovedStatus() {
        Fixture fixture = createFixture(KnowledgeCandidateType.CONCEPT);
        long proposalId = saveProposal(fixture, LlmProposalAction.CREATE, null, "{}");

        assertThatThrownBy(() -> service.convertApproved(proposalId))
                .isInstanceOf(WikiDraftValidationException.class)
                .extracting(error -> ((WikiDraftValidationException) error).reason())
                .isEqualTo(WikiDraftValidationException.Reason.PROPOSAL_NOT_APPROVED);
    }

    @Test
    void keepsMergeTargetUnresolvedAndRejectsCandidateEvidenceLoss() {
        Fixture fixture = createFixture(KnowledgeCandidateType.PROCEDURE);
        long proposalId = saveProposal(fixture, LlmProposalAction.MERGE, "wiki:existing-howto", """
                {
                  "pageType":"HOWTO",
                  "sourceChunkIds":[%d]
                }
                """.formatted(fixture.firstChunkId()), List.of(fixture.firstChunkId()));
        approve(proposalId);

        assertThatThrownBy(() -> service.convertApproved(proposalId))
                .isInstanceOf(WikiDraftValidationException.class)
                .extracting(error -> ((WikiDraftValidationException) error).reason())
                .isEqualTo(WikiDraftValidationException.Reason.INVALID_EVIDENCE);
    }

    private long saveProposal(Fixture fixture, LlmProposalAction action, String target, String normalizedDataJson) {
        return saveProposal(fixture, action, target, normalizedDataJson,
                List.of(fixture.firstChunkId(), fixture.secondChunkId()));
    }

    private long saveProposal(Fixture fixture, LlmProposalAction action, String target, String normalizedDataJson,
                              List<Long> evidenceIds) {
        return proposalRepository.saveDraft(new KnowledgeProposalDraft(
                fixture.workspaceId(), fixture.analysisId(), fixture.documentId(), fixture.candidateId(), action,
                target, "test-provider", "test-model", "document-analysis@test", "v1", "v1", null,
                normalizedDataJson, evidenceIds));
    }

    private void approve(long proposalId) {
        proposalRepository.transitionStatus(proposalId, KnowledgeProposalStatus.DRAFT,
                KnowledgeProposalStatus.REVIEW);
        proposalRepository.transitionStatus(proposalId, KnowledgeProposalStatus.REVIEW,
                KnowledgeProposalStatus.APPROVED);
    }

    private Fixture createFixture(KnowledgeCandidateType candidateType) {
        long workspaceId = insert("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path, data_path, status,
                    created_at, updated_at)
                VALUES ('Wiki Draft Test', '/tmp/wiki-draft', '/tmp/wiki-draft/inbox', '/tmp/wiki-draft/archive',
                    '/tmp/wiki-draft/vault', '/tmp/wiki-draft/data', 'ACTIVE',
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """);
        long documentId = insert("""
                INSERT INTO document (workspace_id, file_name, source_path, sha256, created_at, updated_at)
                VALUES (:workspaceId, 'source.txt', 'source.txt', 'wiki-draft-hash',
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "workspaceId", workspaceId);
        long jobId = insert("""
                INSERT INTO processing_job (workspace_id, job_id, job_type, created_at, updated_at)
                VALUES (:workspaceId, 'WIKI-DRAFT-JOB', 'ANALYZE',
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "workspaceId", workspaceId);
        long jobItemId = insert("""
                INSERT INTO processing_job_item (job_id, document_id) VALUES (:jobId, :documentId)
                """, "jobId", jobId, "documentId", documentId);
        long analysisId = insert("""
                INSERT INTO document_analysis (job_item_id, document_id, status, prompt_identifier, prompt_version,
                    provider, model, contract_version, created_at, updated_at)
                VALUES (:jobItemId, :documentId, 'SUCCEEDED', 'document-analysis@test', 'v1',
                    'test-provider', 'test-model', 'v1', '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "jobItemId", jobItemId, "documentId", documentId);
        long firstChunkId = insertSourceChunk(documentId, 1);
        long secondChunkId = insertSourceChunk(documentId, 2);
        long candidateId = insert("""
                INSERT INTO knowledge_candidate (document_analysis_id, document_id, candidate_no, title,
                    candidate_type, summary, confidence, rationale, created_at, updated_at)
                VALUES (:analysisId, :documentId, 1, 'Candidate Title', :candidateType,
                    'Candidate summary', 0.9, 'Candidate rationale',
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "analysisId", analysisId, "documentId", documentId, "candidateType", candidateType.name());
        insertCandidateEvidence(candidateId, firstChunkId);
        insertCandidateEvidence(candidateId, secondChunkId);
        return new Fixture(workspaceId, documentId, analysisId, candidateId, firstChunkId, secondChunkId);
    }

    private long insertSourceChunk(long documentId, int chunkNo) {
        return insert("""
                INSERT INTO source_chunk (document_id, chunk_no, content, normalized_content, content_hash,
                    created_at, updated_at)
                VALUES (:documentId, :chunkNo, :content, :content, :hash,
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "documentId", documentId, "chunkNo", chunkNo, "content", "Source content " + chunkNo,
                "hash", "chunk-hash-" + chunkNo);
    }

    private void insertCandidateEvidence(long candidateId, long sourceChunkId) {
        db().sql("""
                        INSERT INTO knowledge_candidate_evidence (knowledge_candidate_id, source_chunk_id)
                        VALUES (:candidateId, :sourceChunkId)
                        """)
                .param("candidateId", candidateId)
                .param("sourceChunkId", sourceChunkId)
                .update();
    }

    private long insert(String sql, Object... parameters) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        var statement = db().sql(sql);
        for (int index = 0; index < parameters.length; index += 2) {
            statement = statement.param((String) parameters[index], parameters[index + 1]);
        }
        statement.update(keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new AssertionError("Test insert did not return an id");
        }
        return key.longValue();
    }

    private int count(String table) {
        return db().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private record Fixture(long workspaceId, long documentId, long analysisId, long candidateId,
                           long firstChunkId, long secondChunkId) {
    }
}
