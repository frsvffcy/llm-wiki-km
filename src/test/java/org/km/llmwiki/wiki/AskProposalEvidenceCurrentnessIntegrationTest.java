package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #649 durable ASK evidence currentness: save → REVIEW → APPROVED → Draft →
 * Publish must revalidate the exact SOURCE/WIKI identities proven current at
 * ingress. Stale evidence fails closed at every later boundary; legacy rows
 * without a persisted Wiki revision never invent one.
 */
class AskProposalEvidenceCurrentnessIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProposalAutoDraftService autoDraftService;

    @TempDir
    Path tempDir;

    private long seededChunkId;

    private String requestBody() {
        return """
                {
                  "question": "transformer 的核心架構原則是什麼？",
                  "answerText": "Transformer 以 self-attention 為核心，搭配位置編碼與前饋層。",
                  "provider": "openai-compatible",
                  "model": "gpt-test",
                  "citations": [
                    {"evidenceId": "E1", "kind": "SOURCE", "sourceChunkId": %d},
                    {"evidenceId": "E2", "kind": "WIKI", "wikiPath": "vault/concepts/attention.md", "wikiRevision": 2}
                  ]
                }
                """.formatted(seededChunkId);
    }

    @Test
    void duplicateSaveAfterSourceSupersededFailsClosedInsteadOfReturningDuplicate() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(requestBody()))
                .andExpect(status().isCreated());

        db().sql("UPDATE document SET status = 'SUPERSEDED' WHERE id = :id")
                .param("id", lookupDocumentId()).update();

        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(requestBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ASK_CITATION_INVALID"));

        // No second proposal was created by the stale duplicate attempt.
        assertThat(count("SELECT COUNT(*) FROM knowledge_proposal WHERE source_kind = 'ASK'"))
                .isEqualTo(1);
    }

    @Test
    void approveBlockedAfterSourceDeleted() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(requestBody()))
                .andExpect(status().isCreated());
        long proposalId = firstProposalId();

        db().sql("UPDATE document SET status = 'DELETED' WHERE id = :id")
                .param("id", lookupDocumentId()).update();

        mockMvc.perform(patch("/api/v1/proposals/{id}/status", proposalId)
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ASK_CITATION_INVALID"));

        assertThat(statusOf(proposalId)).isEqualTo("REVIEW");
        assertThat(count("SELECT COUNT(*) FROM wiki_draft WHERE proposal_id = " + proposalId))
                .isEqualTo(0);
    }

    @Test
    void approveBlockedAfterSourceDuplicate() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(requestBody()))
                .andExpect(status().isCreated());
        long proposalId = firstProposalId();

        db().sql("UPDATE document SET status = 'DUPLICATE' WHERE id = :id")
                .param("id", lookupDocumentId()).update();

        mockMvc.perform(patch("/api/v1/proposals/{id}/status", proposalId)
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ASK_CITATION_INVALID"));

        assertThat(statusOf(proposalId)).isEqualTo("REVIEW");
    }

    @Test
    void approveBlockedAfterWikiRevisionAdvances() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(requestBody()))
                .andExpect(status().isCreated());
        long proposalId = firstProposalId();

        // Wiki page advanced from r2 to r3 after the Ask was saved.
        db().sql("UPDATE knowledge_page SET revision = 3 WHERE markdown_path = 'vault/concepts/attention.md'")
                .update();

        mockMvc.perform(patch("/api/v1/proposals/{id}/status", proposalId)
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ASK_CITATION_INVALID"));

        assertThat(statusOf(proposalId)).isEqualTo("REVIEW");
    }

    @Test
    void historicalApprovedWithStaleEvidenceCannotBuildDraft() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(requestBody()))
                .andExpect(status().isCreated());
        long proposalId = firstProposalId();

        // Simulate a historical row approved before the #649 gate, then stale.
        db().sql("UPDATE knowledge_proposal SET status = 'APPROVED' WHERE id = :id")
                .param("id", proposalId).update();
        db().sql("UPDATE document SET status = 'SUPERSEDED' WHERE id = :id")
                .param("id", lookupDocumentId()).update();

        // Manual draft creation fails closed with the existing typed contract.
        mockMvc.perform(post("/api/v1/wiki-drafts")
                        .contentType("application/json")
                        .content("{\"proposalId\":" + proposalId + "}"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(count("SELECT COUNT(*) FROM wiki_draft WHERE proposal_id = " + proposalId))
                .isEqualTo(0);

        // Auto-draft preparation for the same stale APPROVED row also fails typed.
        long workspaceId = lookupWorkspaceId("active");
        ProposalAutoDraft result = autoDraftService.prepare(workspaceId, proposalId);
        assertThat(result.errorCode()).isEqualTo("AUTO_DRAFT_INVALID_EVIDENCE");
        assertThat(result.draftId()).isNull();
    }

    @Test
    void draftBuiltWhenCurrentThenStaleBlocksPublishAndInvalidatesOnRead() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(requestBody()))
                .andExpect(status().isCreated());
        long proposalId = firstProposalId();

        String approved = mockMvc.perform(patch("/api/v1/proposals/{id}/status", proposalId)
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.autoDraft.draftId").isNumber())
                .andReturn().getResponse().getContentAsString();
        long draftId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(approved)
                .path("data").path("autoDraft").path("draftId").asLong();

        // Evidence goes stale after the draft was built.
        db().sql("UPDATE document SET status = 'DELETED' WHERE id = :id")
                .param("id", lookupDocumentId()).update();

        // Publish through the existing READY draft fails closed with the
        // deterministic typed reason (AC-05: publish-time revalidation).
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_PROPOSAL_INVALID"));

        // Reading the draft surfaces the same staleness as a typed invalidation.
        mockMvc.perform(get("/api/v1/wiki-drafts/{id}", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVALIDATED"))
                .andExpect(jsonPath("$.data.invalidatedReason").value("SOURCE_PROPOSAL_INVALID"));
    }

    @Test
    void crossWorkspaceChunkCannotBeSavedAsDuplicateInAnotherWorkspace() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(requestBody()))
                .andExpect(status().isCreated());

        createWorkspace("other");
        activate(lookupWorkspaceId("other"));

        // Same chunk id belongs to "active", not "other": fail closed, no leak.
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(requestBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ASK_CITATION_INVALID"));
    }

    @Test
    void validCurrentJourneyStillApprovesDraftsAndPublishes() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(requestBody()))
                .andExpect(status().isCreated());
        long proposalId = firstProposalId();

        // Persisted snapshot must be versioned with the validated Wiki revision.
        String stored = db().sql("SELECT ask_citations_json FROM knowledge_proposal WHERE id = :id")
                .param("id", proposalId).query(String.class).single();
        assertThat(stored).contains("\"version\":1");
        assertThat(stored).contains("\"wikiRevision\":2");

        String approved = mockMvc.perform(patch("/api/v1/proposals/{id}/status", proposalId)
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.autoDraft.draftId").isNumber())
                .andReturn().getResponse().getContentAsString();
        long draftId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(approved)
                .path("data").path("autoDraft").path("draftId").asLong();

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"));

        mockMvc.perform(get("/api/v1/wiki").param("page", "0").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    void legacySnapshotWithoutWikiRevisionFailsClosedAtApproveAndDraft() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(requestBody()))
                .andExpect(status().isCreated());
        long proposalId = firstProposalId();

        // Downgrade the persisted snapshot to the pre-#649 legacy bare-array
        // format (no wikiRevision): the system must not invent a revision.
        String legacy = "[{\"evidenceId\":\"E1\",\"kind\":\"SOURCE\",\"sourceChunkId\":" + seededChunkId + "},"
                + "{\"evidenceId\":\"E2\",\"kind\":\"WIKI\",\"wikiPath\":\"vault/concepts/attention.md\"}]";
        db().sql("UPDATE knowledge_proposal SET ask_citations_json = :json WHERE id = :id")
                .param("json", legacy).param("id", proposalId).update();

        mockMvc.perform(patch("/api/v1/proposals/{id}/status", proposalId)
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ASK_CITATION_INVALID"));
        assertThat(statusOf(proposalId)).isEqualTo("REVIEW");

        // Historical legacy APPROVED rows also fail closed at draft creation.
        db().sql("UPDATE knowledge_proposal SET status = 'APPROVED' WHERE id = :id")
                .param("id", proposalId).update();
        mockMvc.perform(post("/api/v1/wiki-drafts")
                        .contentType("application/json")
                        .content("{\"proposalId\":" + proposalId + "}"))
                .andExpect(status().is4xxClientError());
        assertThat(count("SELECT COUNT(*) FROM wiki_draft WHERE proposal_id = " + proposalId))
                .isEqualTo(0);
    }

    private void activate(long workspaceId) {
        db().sql("UPDATE workspace SET status = 'INACTIVE'").update();
        db().sql("UPDATE workspace SET status = 'ACTIVE' WHERE id = :id")
                .param("id", workspaceId).update();
    }

    private long firstProposalId() {
        return db().sql("SELECT id FROM knowledge_proposal WHERE source_kind = 'ASK'")
                .query(Long.class).single();
    }

    private long lookupDocumentId() {
        return db().sql("SELECT id FROM document WHERE workspace_id = :ws")
                .param("ws", lookupWorkspaceId("active")).query(Long.class).single();
    }

    private String statusOf(long proposalId) {
        return db().sql("SELECT status FROM knowledge_proposal WHERE id = :id")
                .param("id", proposalId).query(String.class).single();
    }

    private int count(String sql) {
        return db().sql(sql).query(Integer.class).single();
    }

    private void seedWorkspaceWithSources() throws Exception {
        createWorkspace("active");
        long documentId = insert("""
                INSERT INTO document (workspace_id, file_name, source_path, sha256, status, created_at, updated_at)
                VALUES (:ws, 'source.txt', 'source.txt', 'hash', 'PROCESSED', :now, :now)
                """, "ws", lookupWorkspaceId("active"), "now", "2026-09-01T00:00:00Z");
        seededChunkId = insert("""
                INSERT INTO source_chunk (document_id, chunk_no, content, normalized_content, content_hash,
                    created_at, updated_at)
                VALUES (:document, 1, 'chunk content', 'chunk content', 'chunk-hash', :now, :now)
                """, "document", documentId, "now", "2026-09-01T00:00:00Z");
        insert("""
                INSERT INTO knowledge_page (workspace_id, knowledge_id, title, normalized_title, type,
                    markdown_path, status, content_hash, revision, created_at, updated_at)
                VALUES (:ws, 'wiki-attention', 'Attention', 'attention', 'CONCEPT',
                    'vault/concepts/attention.md', 'PUBLISHED', 'hash', 2, :now, :now)
                """, "ws", lookupWorkspaceId("active"), "now", "2026-09-01T00:00:00Z");
    }

    private void createWorkspace(String name) throws Exception {
        Path root = tempDir.resolve(name + "-" + System.nanoTime());
        Files.createDirectories(root.resolve("vault/concepts"));
        Files.createDirectories(root.resolve("inbox"));
        Files.createDirectories(root.resolve("archive"));
        Files.createDirectories(root.resolve("data"));
        insert("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path, data_path, status,
                    created_at, updated_at)
                VALUES (:name, :root, :inbox, :archive, :vault, :data, 'ACTIVE', :now, :now)
                """, "name", name, "root", root.toString(), "inbox", root.resolve("inbox").toString(),
                "archive", root.resolve("archive").toString(), "vault", root.resolve("vault").toString(),
                "data", root.resolve("data").toString(), "now", "2026-09-01T00:00:00Z");
    }

    private long lookupWorkspaceId(String name) {
        return db().sql("SELECT id FROM workspace WHERE name = :name").param("name", name)
                .query(Long.class).single();
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
            throw new IllegalStateException("insert did not return a key");
        }
        return key.longValue();
    }
}
