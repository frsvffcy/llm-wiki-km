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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Governed Ask -> Proposal ingress (#374): a separate mutation command from the
 * read-only Ask surface. Backend authority rules: citations must resolve to current
 * workspace-scoped sources (typed fail-closed), identical submissions deduplicate,
 * and the created proposal enters the existing lifecycle at REVIEW with no auto-publish.
 */
class AskProposalIngressIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private org.jooq.DSLContext dslContext;

    private long seededChunkId;


    @TempDir
    Path tempDir;



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
    void createsAReviewProposalThroughTheSeparateMutationCommand() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));

        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(requestBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.proposal.id").isNumber())
                .andExpect(jsonPath("$.data.proposal.status").value("REVIEW"))
                .andExpect(jsonPath("$.data.proposal.allowedTransitions.length()").value(2))
                .andExpect(jsonPath("$.data.proposal.allowedTransitions[0]").value("APPROVED"))
                .andExpect(jsonPath("$.data.proposal.evidence[0].sourceChunkId")
                        .value(seededChunkId))
                .andExpect(jsonPath("$.data.duplicate").value(false));

        // The proposal enters the existing lifecycle: REVIEW -> APPROVED (human decision)
        // -> Draft -> ... Publish. Approval does not auto-publish anything.
        mockMvc.perform(patch("/api/v1/proposals/{id}/status", firstProposalId())
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowedTransitions.length()").value(0));

        mockMvc.perform(post("/api/v1/wiki-drafts")
                        .contentType("application/json")
                        .content("{\"proposalId\":"
                                + firstProposalId() + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.proposalId").value(firstProposalId()))
                .andExpect(jsonPath("$.data.sourceChunkIds[0]").value(seededChunkId));
    }

    @Test
    void identicalSubmissionsDeduplicateToTheSameProposal() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(requestBody()))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(true))
                .andExpect(jsonPath("$.data.proposal.id").value(firstProposalId()));

        mockMvc.perform(get("/api/v1/proposals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void duplicateAskInsertLosesTheRaceDeterministically() throws Exception {
        seedWorkspaceWithSources();
        long workspaceId = lookupWorkspaceId("active");
        AskProposalIngressRepository repository = new AskProposalIngressRepository(dslContext);
        CreateAskProposalRequest request =
                new CreateAskProposalRequest("q", "a", "p", "m", java.util.List.of());
        long first = repository.insertAskProposal(workspaceId, request, "{\"title\":\"T\"}", "[]",
                java.util.List.of(seededChunkId), "ask-dedup-1");
        org.assertj.core.api.Assertions.assertThat(first).isPositive();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.insertAskProposal(
                        workspaceId, request, "{\"title\":\"T\"}", "[]",
                        java.util.List.of(seededChunkId), "ask-dedup-1"))
                .isInstanceOf(DuplicateAskProposalException.class);
    }

    @Test
    void staleWikiAndUnknownChunkCitationsFailClosedWithoutPersisting() throws Exception {
        seedWorkspaceWithSources();
        activate(lookupWorkspaceId("active"));
        String staleWiki = """
                {
                  "question": "q",
                  "answerText": "a",
                  "provider": "p",
                  "model": "m",
                  "citations": [
                    {"evidenceId": "E1", "kind": "WIKI", "wikiPath": "vault/concepts/attention.md", "wikiRevision": 1}
                  ]
                }
                """;
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(staleWiki))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ASK_CITATION_INVALID"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getContentAsString()).contains("attention.md"));

        String unknownChunk = """
                {
                  "question": "q",
                  "answerText": "a",
                  "provider": "p",
                  "model": "m",
                  "citations": [
                    {"evidenceId": "E1", "kind": "SOURCE", "sourceChunkId": 987654}
                  ]
                }
                """;
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(unknownChunk))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ASK_CITATION_INVALID"));

        mockMvc.perform(get("/api/v1/proposals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void foreignWorkspaceChunkIsIndistinguishableFromUnknown() throws Exception {
        createWorkspace("other");
        long otherWorkspaceId = lookupWorkspaceId("other");
        createWorkspace("active");
        activate(lookupWorkspaceId("active"));
        long foreignDocumentId = insert("""
                INSERT INTO document (workspace_id, file_name, source_path, sha256, status, created_at, updated_at)
                VALUES (:ws, 'foreign.txt', 'foreign.txt', 'foreign-doc', 'PROCESSED', :now, :now)
                """, "ws", otherWorkspaceId, "now", "2026-09-01T00:00:00Z");
        long foreignChunk = insert("""
                INSERT INTO source_chunk (document_id, chunk_no, content, normalized_content, content_hash,
                    created_at, updated_at)
                VALUES (:document, 1, 'foreign', 'foreign', 'foreign-hash', :now, :now)
                """, "document", foreignDocumentId, "now", "2026-09-01T00:00:00Z");
        String body = "{\"question\":\"q\",\"answerText\":\"a\",\"provider\":\"p\",\"model\":\"m\","
                + "\"citations\":[{\"evidenceId\":\"E1\",\"kind\":\"SOURCE\",\"sourceChunkId\":"
                + foreignChunk + "}]}";
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ASK_CITATION_INVALID"));
    }

    @Test
    void invalidRequestsAreTyped400s() throws Exception {
        createWorkspace("active");
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json")
                        .content("{\"question\":\"q\",\"answerText\":\"a\",\"citations\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json")
                        .content("{\"question\":\"q\",\"answerText\":\"a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void withoutActiveWorkspaceTheIngressFailsTyped() throws Exception {
        mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content(requestBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_WORKSPACE"));
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
        Path root = tempDir.resolve(name);
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
