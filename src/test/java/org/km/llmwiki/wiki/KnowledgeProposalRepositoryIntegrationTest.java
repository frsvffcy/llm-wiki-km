package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.LlmProposalAction;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "app.persistence.sqlite.path=target/test-data/proposals-${random.uuid}/knowledge.db"
})
class KnowledgeProposalRepositoryIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private KnowledgeProposalRepository repository;

    @Test
    void persistsDraftWithCandidateEvidenceMetadataAndMergeReference() {
        Fixture fixture = createFixture();

        long proposalId = repository.saveDraft(draft(fixture, List.of(fixture.firstSourceChunkId(), fixture.secondSourceChunkId())));

        KnowledgeProposal proposal = repository.findById(proposalId).orElseThrow();
        assertThat(proposal.workspaceId()).isEqualTo(fixture.workspaceId());
        assertThat(proposal.documentId()).isEqualTo(fixture.documentId());
        assertThat(proposal.documentAnalysisId()).isEqualTo(fixture.documentAnalysisId());
        assertThat(proposal.knowledgeCandidateId()).isEqualTo(fixture.candidateId());
        assertThat(proposal.action()).isEqualTo(LlmProposalAction.MERGE);
        assertThat(proposal.status()).isEqualTo(KnowledgeProposalStatus.DRAFT);
        assertThat(proposal.mergeTargetReference()).isEqualTo("wiki:existing-topic");
        assertThat(proposal.provider()).isEqualTo("test-provider");
        assertThat(proposal.model()).isEqualTo("test-model");
        assertThat(proposal.promptIdentifier()).isEqualTo("document-analysis@test");
        assertThat(proposal.promptVersion()).isEqualTo("v1");
        assertThat(proposal.contractVersion()).isEqualTo("v1");
        assertThat(proposal.validatedPayloadJson()).isEqualTo("{\"action\":\"MERGE\"}");
        assertThat(proposal.normalizedDataJson()).isEqualTo("{\"title\":\"測試 Proposal\"}");
        assertThat(proposal.evidenceSourceChunkIds())
                .containsExactly(fixture.firstSourceChunkId(), fixture.secondSourceChunkId());
    }

    @Test
    void permitsOnlyTheDefinedReviewLifecycleTransitions() {
        Fixture fixture = createFixture();
        long proposalId = repository.saveDraft(draft(fixture, List.of(fixture.firstSourceChunkId())));

        assertThatIllegalArgumentException().isThrownBy(() -> repository.transitionStatus(
                proposalId, KnowledgeProposalStatus.DRAFT, KnowledgeProposalStatus.APPROVED));
        repository.transitionStatus(proposalId, KnowledgeProposalStatus.DRAFT, KnowledgeProposalStatus.REVIEW);
        repository.transitionStatus(proposalId, KnowledgeProposalStatus.REVIEW, KnowledgeProposalStatus.APPROVED);

        assertThat(repository.findById(proposalId).orElseThrow().status()).isEqualTo(KnowledgeProposalStatus.APPROVED);
        assertThatIllegalArgumentException().isThrownBy(() -> repository.transitionStatus(
                proposalId, KnowledgeProposalStatus.APPROVED, KnowledgeProposalStatus.REJECTED));
    }

    @Test
    void rejectsInvalidActionsSecretsAndEvidenceOutsideTheProposalDocument() {
        Fixture fixture = createFixture();

        assertThatNullPointerException().isThrownBy(() -> new KnowledgeProposalDraft(
                fixture.workspaceId(), fixture.documentAnalysisId(), fixture.documentId(), fixture.candidateId(), null,
                null, "test-provider", "test-model", "document-analysis@test", "v1", "v1", null,
                "{\"title\":\"測試 Proposal\"}", List.of(fixture.firstSourceChunkId())))
                .withMessageContaining("action must not be null");
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeProposalDraft(
                fixture.workspaceId(), fixture.documentAnalysisId(), fixture.documentId(), fixture.candidateId(),
                LlmProposalAction.CREATE, null, "test-provider", "test-model", "document-analysis@test", "v1", "v1",
                "{\"apiKey\":\"must-not-be-stored\"}", "{\"title\":\"測試 Proposal\"}",
                List.of(fixture.firstSourceChunkId())))
                .withMessageContaining("must not contain secrets");

        long unrelatedSourceChunkId = insertSourceChunk(createOtherDocument(fixture.workspaceId()), 1);
        assertThatIllegalArgumentException().isThrownBy(() -> repository.saveDraft(
                draft(fixture, List.of(unrelatedSourceChunkId))))
                .withMessageContaining("proposal evidence must belong to document");
        assertThat(count("knowledge_proposal")).isZero();
    }

    @Test
    void rollsBackProposalWhenEvidenceAssociationFailsAfterProposalInsert() {
        Fixture fixture = createFixture();
        db().sql("""
                        CREATE TRIGGER fail_second_proposal_evidence
                        BEFORE INSERT ON knowledge_proposal_evidence
                        WHEN NEW.source_chunk_id = %d
                        BEGIN
                            SELECT RAISE(ABORT, 'test evidence failure');
                        END
                        """.formatted(fixture.secondSourceChunkId())).update();

        assertThatThrownBy(() -> repository.saveDraft(
                draft(fixture, List.of(fixture.firstSourceChunkId(), fixture.secondSourceChunkId()))))
                .isInstanceOf(RuntimeException.class);

        assertThat(count("knowledge_proposal")).isZero();
        assertThat(count("knowledge_proposal_evidence")).isZero();
    }

    @Test
    void databaseRejectsUnknownActionAndStatusValues() {
        Fixture fixture = createFixture();

        assertThatThrownBy(() -> db().sql("""
                        INSERT INTO knowledge_proposal (
                            workspace_id, document_analysis_id, document_id, knowledge_candidate_id, action, status,
                            provider, model, prompt_identifier, prompt_version, contract_version, normalized_data_json,
                            created_at, updated_at)
                        VALUES (:workspaceId, :analysisId, :documentId, :candidateId, 'PUBLISH', 'PENDING',
                            'provider', 'model', 'prompt', 'v1', 'v1', '{}', '2026-08-27T00:00:00Z', '2026-08-27T00:00:00Z')
                        """).param("workspaceId", fixture.workspaceId()).param("analysisId", fixture.documentAnalysisId())
                .param("documentId", fixture.documentId()).param("candidateId", fixture.candidateId()).update())
                .isInstanceOf(RuntimeException.class);
    }

    private KnowledgeProposalDraft draft(Fixture fixture, List<Long> evidenceIds) {
        return new KnowledgeProposalDraft(fixture.workspaceId(), fixture.documentAnalysisId(), fixture.documentId(),
                fixture.candidateId(), LlmProposalAction.MERGE, "wiki:existing-topic", "test-provider", "test-model",
                "document-analysis@test", "v1", "v1", "{\"action\":\"MERGE\"}",
                "{\"title\":\"測試 Proposal\"}", evidenceIds);
    }

    private Fixture createFixture() {
        long workspaceId = insert("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path, data_path, status, created_at, updated_at)
                VALUES ('Proposal Test', '/tmp/proposal', '/tmp/proposal/inbox', '/tmp/proposal/archive',
                    '/tmp/proposal/vault', '/tmp/proposal/data', 'ACTIVE', '2026-08-27T00:00:00Z', '2026-08-27T00:00:00Z')
                """);
        long documentId = createDocument(workspaceId, "proposal.txt");
        long jobId = insert("""
                INSERT INTO processing_job (workspace_id, job_id, job_type, created_at, updated_at)
                VALUES (:workspaceId, 'PROPOSAL-TEST-JOB', 'ANALYZE', '2026-08-27T00:00:00Z', '2026-08-27T00:00:00Z')
                """, "workspaceId", workspaceId);
        long jobItemId = insert("""
                INSERT INTO processing_job_item (job_id, document_id)
                VALUES (:jobId, :documentId)
                """, "jobId", jobId, "documentId", documentId);
        long analysisId = insert("""
                INSERT INTO document_analysis (job_item_id, document_id, status, prompt_identifier, prompt_version,
                    provider, model, contract_version, created_at, updated_at)
                VALUES (:jobItemId, :documentId, 'SUCCEEDED', 'document-analysis@test', 'v1',
                    'test-provider', 'test-model', 'v1', '2026-08-27T00:00:00Z', '2026-08-27T00:00:00Z')
                """, "jobItemId", jobItemId, "documentId", documentId);
        long firstSourceChunkId = insertSourceChunk(documentId, 1);
        long secondSourceChunkId = insertSourceChunk(documentId, 2);
        long candidateId = insert("""
                INSERT INTO knowledge_candidate (document_analysis_id, document_id, candidate_no, title, candidate_type,
                    summary, confidence, rationale, created_at, updated_at)
                VALUES (:analysisId, :documentId, 1, '測試 Candidate', 'CONCEPT', '摘要', 0.9, '理由',
                    '2026-08-27T00:00:00Z', '2026-08-27T00:00:00Z')
                """, "analysisId", analysisId, "documentId", documentId);
        db().sql("""
                        INSERT INTO knowledge_candidate_evidence (knowledge_candidate_id, source_chunk_id)
                        VALUES (:candidateId, :sourceChunkId)
                        """).param("candidateId", candidateId).param("sourceChunkId", firstSourceChunkId).update();
        return new Fixture(workspaceId, documentId, analysisId, candidateId, firstSourceChunkId, secondSourceChunkId);
    }

    private long createOtherDocument(long workspaceId) {
        return createDocument(workspaceId, "unrelated.txt");
    }

    private long createDocument(long workspaceId, String fileName) {
        return insert("""
                INSERT INTO document (workspace_id, file_name, source_path, sha256, created_at, updated_at)
                VALUES (:workspaceId, :fileName, :fileName, :fileName, '2026-08-27T00:00:00Z', '2026-08-27T00:00:00Z')
                """, "workspaceId", workspaceId, "fileName", fileName);
    }

    private long insertSourceChunk(long documentId, int chunkNo) {
        return insert("""
                INSERT INTO source_chunk (document_id, chunk_no, content, normalized_content, content_hash, created_at, updated_at)
                VALUES (:documentId, :chunkNo, '來源內容', '來源內容', :contentHash,
                    '2026-08-27T00:00:00Z', '2026-08-27T00:00:00Z')
                """, "documentId", documentId, "chunkNo", chunkNo, "contentHash", "hash-" + documentId + "-" + chunkNo);
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
            throw new AssertionError("測試資料新增後未取得 id");
        }
        return key.longValue();
    }

    private int count(String table) {
        return db().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private record Fixture(long workspaceId, long documentId, long documentAnalysisId, long candidateId,
                           long firstSourceChunkId, long secondSourceChunkId) {
    }
}
