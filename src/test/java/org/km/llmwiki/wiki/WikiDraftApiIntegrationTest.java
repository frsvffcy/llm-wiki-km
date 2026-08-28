package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.ai.LlmProposalAction;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.persistence.sqlite.path=target/test-data/wiki-draft-api-${random.uuid}/knowledge.db")
@AutoConfigureMockMvc
class WikiDraftApiIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @TempDir
    Path tempDir;

    @Test
    void createsGetsPreviewsAndDiffsDeterministicallyWithoutWritingVault() throws Exception {
        Workspace workspace = createWorkspace("active", "ACTIVE");
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "Deterministic Topic");
        Path target = workspace.root().resolve("vault/concepts/deterministic-topic.md");

        long firstId = createDraft(proposal.id());
        long secondId = createDraft(proposal.id());
        String firstPreview = mockMvc.perform(get("/api/v1/wiki-drafts/{id}/preview", firstId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.publishReady").value(true))
                .andExpect(jsonPath("$.data.proposalId").value(proposal.id()))
                .andExpect(jsonPath("$.data.action").value("CREATE"))
                .andExpect(jsonPath("$.data.targetPath").value("vault/concepts/deterministic-topic.md"))
                .andExpect(jsonPath("$.data.sourceChunkIds[0]").isNumber())
                .andExpect(jsonPath("$.data.evidence[0].sourceChunkId").isNumber())
                .andExpect(jsonPath("$.data.markdown").value(org.hamcrest.Matchers.containsString(
                        "# Deterministic Topic")))
                .andReturn().getResponse().getContentAsString();
        String secondPreview = mockMvc.perform(get("/api/v1/wiki-drafts/{id}/preview", secondId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String firstHash = json(firstPreview, "/data/renderedContentHash");
        assertThat(json(secondPreview, "/data/renderedContentHash")).isEqualTo(firstHash);
        assertThat(json(secondPreview, "/data/markdown")).isEqualTo(json(firstPreview, "/data/markdown"));
        mockMvc.perform(get("/api/v1/wiki-drafts/{id}", firstId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposalId").value(proposal.id()))
                .andExpect(jsonPath("$.data.sourceChunkIds[0]").isNumber())
                .andExpect(jsonPath("$.data.targetPath").value("vault/concepts/deterministic-topic.md"));
        mockMvc.perform(get("/api/v1/wiki-drafts/{id}/diff", firstId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentContent").value(""))
                .andExpect(jsonPath("$.data.unifiedDiff").value(org.hamcrest.Matchers.containsString(
                        "+++ b/vault/concepts/deterministic-topic.md")));
        assertThat(Files.exists(target)).isFalse();
        assertThat(proposalStatus(proposal.id())).isEqualTo("APPROVED");
    }

    @Test
    void invalidatesAndRegeneratesWithoutRollingBackApprovedProposal() throws Exception {
        Workspace workspace = createWorkspace("regenerate", "ACTIVE");
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "Regeneration Topic");
        long oldId = createDraft(proposal.id());

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/invalidate", oldId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVALIDATED"))
                .andExpect(jsonPath("$.data.invalidatedReason").value("MANUAL"))
                .andExpect(jsonPath("$.data.publishReady").value(false));
        String regenerated = mockMvc.perform(post("/api/v1/wiki-drafts/{id}/regenerate", oldId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.regeneratedFromDraftId").value(oldId))
                .andReturn().getResponse().getContentAsString();

        assertThat(Long.parseLong(json(regenerated, "/data/id"))).isNotEqualTo(oldId);
        long regeneratedId = Long.parseLong(json(regenerated, "/data/id"));
        String secondRegeneration = mockMvc.perform(post("/api/v1/wiki-drafts/{id}/regenerate", regeneratedId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.regeneratedFromDraftId").value(regeneratedId))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(get("/api/v1/wiki-drafts/{id}", regeneratedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVALIDATED"))
                .andExpect(jsonPath("$.data.invalidatedReason").value("SUPERSEDED_BY_REGENERATION"))
                .andExpect(jsonPath("$.data.publishReady").value(false));
        assertThat(Long.parseLong(json(secondRegeneration, "/data/id"))).isNotEqualTo(regeneratedId);
        assertThat(proposalStatus(proposal.id())).isEqualTo("APPROVED");
        assertThat(count("wiki_draft")).isEqualTo(3);
    }

    @Test
    void invalidatesReadyCreateDraftWhenItsTargetAppears() throws Exception {
        Workspace workspace = createWorkspace("drift", "ACTIVE");
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "Drift Topic");
        long draftId = createDraft(proposal.id());
        Files.writeString(workspace.root().resolve("vault/concepts/drift-topic.md"), "external change\n");

        mockMvc.perform(get("/api/v1/wiki-drafts/{id}", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVALIDATED"))
                .andExpect(jsonPath("$.data.invalidatedReason").value("TARGET_CHANGED"))
                .andExpect(jsonPath("$.data.publishReady").value(false));
        assertThat(proposalStatus(proposal.id())).isEqualTo("APPROVED");
    }

    @Test
    void returnsAuditableMergeBaselineDiffAndRejectsChangedTargetAtCreation() throws Exception {
        Workspace workspace = createWorkspace("merge", "ACTIVE");
        String baseline = "# Existing Topic\n\nOld content\n";
        Path target = workspace.root().resolve("vault/concepts/existing-topic.md");
        Files.writeString(target, baseline);
        String hash = WikiContentHash.sha256(Files.readAllBytes(target));
        insertKnowledgePage(workspace.id(), "existing-topic", "Existing Topic", hash);
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.MERGE, "wiki:existing-topic",
                KnowledgeProposalStatus.APPROVED, "Candidate Addition");

        long draftId = createDraft(proposal.id());
        mockMvc.perform(get("/api/v1/wiki-drafts/{id}/diff", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentContent").value(baseline))
                .andExpect(jsonPath("$.data.targetPath").value("vault/concepts/existing-topic.md"))
                .andExpect(jsonPath("$.data.unifiedDiff").value(org.hamcrest.Matchers.containsString(
                        "-Old content")));
        assertThat(Files.readString(target)).isEqualTo(baseline);

        Files.writeString(target, "changed after planning\n");
        mockMvc.perform(post("/api/v1/wiki-drafts").contentType("application/json")
                        .content("{\"proposalId\":" + proposal.id() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WIKI_DRAFT_TARGET_TARGET_CONTENT_HASH_MISMATCH"));
        assertThat(count("wiki_draft")).isEqualTo(1);
    }

    @Test
    void isolatesDraftIdsByActiveWorkspaceAndHandlesInvalidInputs() throws Exception {
        Workspace active = createWorkspace("isolation-active", "ACTIVE");
        Proposal activeProposal = createProposal(active.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "Active Topic");
        long draftId = createDraft(activeProposal.id());
        Workspace foreign = createWorkspace("isolation-foreign", "INACTIVE");
        Proposal foreignProposal = createProposal(foreign.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "Foreign Topic");

        activate(foreign.id());
        mockMvc.perform(get("/api/v1/wiki-drafts/{id}", draftId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WIKI_DRAFT_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/wiki-drafts").contentType("application/json")
                        .content("{\"proposalId\":" + activeProposal.id() + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("KNOWLEDGE_PROPOSAL_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/wiki-drafts").contentType("application/json")
                        .content("{\"proposalId\":" + foreignProposal.id() + "}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/wiki-drafts/{id}", 999999))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsUnapprovedProposalWithoutPersistingDraft() throws Exception {
        Workspace workspace = createWorkspace("invalid-proposal", "ACTIVE");
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.REVIEW, "Review Topic");

        mockMvc.perform(post("/api/v1/wiki-drafts").contentType("application/json")
                        .content("{\"proposalId\":" + proposal.id() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(count("wiki_draft")).isZero();
        assertThat(proposalStatus(proposal.id())).isEqualTo("REVIEW");
    }

    @Test
    void invalidatesReadyDraftWhenItsPersistedProposalIsNoLongerApproved() throws Exception {
        Workspace workspace = createWorkspace("proposal-drift", "ACTIVE");
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "Proposal Drift Topic");
        long draftId = createDraft(proposal.id());
        db().sql("UPDATE knowledge_proposal SET status = 'REVIEW' WHERE id = :id")
                .param("id", proposal.id()).update();

        mockMvc.perform(get("/api/v1/wiki-drafts/{id}", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVALIDATED"))
                .andExpect(jsonPath("$.data.invalidatedReason").value("SOURCE_PROPOSAL_INVALID"))
                .andExpect(jsonPath("$.data.publishReady").value(false));
        assertThat(proposalStatus(proposal.id())).isEqualTo("REVIEW");
    }

    private long createDraft(long proposalId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/wiki-drafts").contentType("application/json")
                        .content("{\"proposalId\":" + proposalId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(json(response, "/data/id"));
    }

    private Workspace createWorkspace(String name, String status) throws Exception {
        Path root = tempDir.resolve(name);
        Files.createDirectories(root.resolve("vault/concepts"));
        Files.createDirectories(root.resolve("inbox"));
        Files.createDirectories(root.resolve("archive"));
        Files.createDirectories(root.resolve("data"));
        long id = insert("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path, data_path, status,
                    created_at, updated_at)
                VALUES (:name, :root, :inbox, :archive, :vault, :data, :status, :now, :now)
                """, "name", name, "root", root.toString(), "inbox", root.resolve("inbox").toString(),
                "archive", root.resolve("archive").toString(), "vault", root.resolve("vault").toString(),
                "data", root.resolve("data").toString(), "status", status, "now", "2026-08-29T00:00:00Z");
        return new Workspace(id, root);
    }

    private Proposal createProposal(long workspaceId, LlmProposalAction action, String target,
                                    KnowledgeProposalStatus status, String title) {
        String suffix = workspaceId + "-" + title;
        long documentId = insert("""
                INSERT INTO document (workspace_id, file_name, source_path, sha256, status, created_at, updated_at)
                VALUES (:workspaceId, :fileName, :fileName, :hash, 'PROCESSED', :now, :now)
                """, "workspaceId", workspaceId, "fileName", suffix + ".txt", "hash", "document-" + suffix,
                "now", "2026-08-29T00:00:00Z");
        long jobId = insert("""
                INSERT INTO processing_job (workspace_id, job_id, job_type, created_at, updated_at)
                VALUES (:workspaceId, :jobId, 'ANALYZE', :now, :now)
                """, "workspaceId", workspaceId, "jobId", "JOB-" + suffix,
                "now", "2026-08-29T00:00:00Z");
        long jobItemId = insert("INSERT INTO processing_job_item (job_id, document_id) VALUES (:job, :document)",
                "job", jobId, "document", documentId);
        long analysisId = insert("""
                INSERT INTO document_analysis (job_item_id, document_id, status, prompt_identifier, prompt_version,
                    provider, model, contract_version, created_at, updated_at)
                VALUES (:jobItem, :document, 'SUCCEEDED', 'prompt', 'v1', 'provider', 'model', 'v1', :now, :now)
                """, "jobItem", jobItemId, "document", documentId, "now", "2026-08-29T00:00:00Z");
        long chunkId = insert("""
                INSERT INTO source_chunk (document_id, chunk_no, content, normalized_content, content_hash,
                    created_at, updated_at)
                VALUES (:document, 1, 'Evidence content', 'Evidence content', :hash, :now, :now)
                """, "document", documentId, "hash", "chunk-" + suffix, "now", "2026-08-29T00:00:00Z");
        long candidateId = insert("""
                INSERT INTO knowledge_candidate (document_analysis_id, document_id, candidate_no, title,
                    candidate_type, summary, confidence, rationale, created_at, updated_at)
                VALUES (:analysis, :document, 1, :title, 'CONCEPT', 'Candidate summary', 0.9, 'Rationale', :now, :now)
                """, "analysis", analysisId, "document", documentId, "title", title,
                "now", "2026-08-29T00:00:00Z");
        db().sql("""
                INSERT INTO knowledge_candidate_evidence (knowledge_candidate_id, source_chunk_id)
                VALUES (:candidate, :chunk)
                """).param("candidate", candidateId).param("chunk", chunkId).update();
        String normalized = """
                {"title":"%s","pageType":"CONCEPT","summary":"Draft summary",
                 "sections":[{"heading":"Summary","content":"Rendered content"}],"sourceChunkIds":[%d]}
                """.formatted(title, chunkId);
        long proposalId = insert("""
                INSERT INTO knowledge_proposal (workspace_id, document_analysis_id, document_id,
                    knowledge_candidate_id, action, status, merge_target_reference, provider, model,
                    prompt_identifier, prompt_version, contract_version, normalized_data_json, created_at, updated_at)
                VALUES (:workspace, :analysis, :document, :candidate, :action, :status, :target,
                    'provider', 'model', 'prompt', 'v1', 'v1', :normalized, :now, :now)
                """, "workspace", workspaceId, "analysis", analysisId, "document", documentId,
                "candidate", candidateId, "action", action.name(), "status", status.name(), "target", target,
                "normalized", normalized, "now", "2026-08-29T00:00:00Z");
        db().sql("""
                INSERT INTO knowledge_proposal_evidence (knowledge_proposal_id, source_chunk_id)
                VALUES (:proposal, :chunk)
                """).param("proposal", proposalId).param("chunk", chunkId).update();
        return new Proposal(proposalId);
    }

    private void insertKnowledgePage(long workspaceId, String knowledgeId, String title, String hash) {
        db().sql("""
                INSERT INTO knowledge_page (workspace_id, knowledge_id, title, normalized_title, type,
                    markdown_path, status, content_hash, created_at, updated_at)
                VALUES (:workspace, :knowledgeId, :title, :normalizedTitle, 'CONCEPT', :path,
                    'PUBLISHED', :hash, :now, :now)
                """).param("workspace", workspaceId).param("knowledgeId", knowledgeId).param("title", title)
                .param("normalizedTitle", WikiTargetReference.normalizeTitle(title))
                .param("path", "vault/concepts/existing-topic.md").param("hash", hash)
                .param("now", "2026-08-29T00:00:00Z").update();
    }

    private void activate(long workspaceId) {
        db().sql("UPDATE workspace SET status = 'INACTIVE'").update();
        db().sql("UPDATE workspace SET status = 'ACTIVE' WHERE id = :id").param("id", workspaceId).update();
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

    private String proposalStatus(long proposalId) {
        return db().sql("SELECT status FROM knowledge_proposal WHERE id = :id")
                .param("id", proposalId).query(String.class).single();
    }

    private int count(String table) {
        return db().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private static String json(String body, String pointer) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).at(pointer).asText();
    }

    private record Workspace(long id, Path root) {
    }

    private record Proposal(long id) {
    }
}
