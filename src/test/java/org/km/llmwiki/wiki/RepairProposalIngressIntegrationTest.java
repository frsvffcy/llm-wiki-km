package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Governed repair ingress (#384): an explicit human repair intent for one canonical
 * page identity revalidates finding/currentness/eligibility at command time and enters
 * the unchanged Proposal → Draft → Human Review → Publish flow. Nothing writes
 * vault/archive except the explicit publish step; stale, ineligible, foreign, and
 * duplicate commands fail closed with typed errors.
 */
class RepairProposalIngressIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private org.jooq.DSLContext dslContext;

    @Autowired
    private org.km.llmwiki.wiki.RepairProposalIngressService ingressService;

    @TempDir
    Path tempDir;

    @Test
    void repairsManuallySeededDriftedPageWithoutPublishing() throws Exception {
        Workspace active = createWorkspace("active");
        activate(active.id());
        long documentId = insert("""
                INSERT INTO document (workspace_id, file_name, source_path, sha256, status, created_at, updated_at)
                VALUES (:ws, 'source.txt', 'source.txt', 'hash', 'PROCESSED', :now, :now)
                """, "ws", active.id(), "now", "2026-09-01T00:00:00Z");
        long chunkId = insert("""
                INSERT INTO source_chunk (document_id, chunk_no, content, normalized_content, content_hash,
                    created_at, updated_at)
                VALUES (:document, 1, 'chunk content', 'chunk content', 'chunk-hash', :now, :now)
                """, "document", documentId, "now", "2026-09-01T00:00:00Z");
        MvcResult ask = mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content("""
                                {"question": "q?",
                                 "answerText": "a.",
                                 "provider": "p",
                                 "model": "m",
                                 "citations": [{"evidenceId": "E1", "kind": "SOURCE", "sourceChunkId": %d}]}
                                """.formatted(chunkId)))
                .andExpect(status().isCreated())
                .andReturn();
        long askId = ((Number) com.jayway.jsonpath.JsonPath.read(ask.getResponse().getContentAsString(),
                "$.data.proposal.id")).longValue();
        seedPublishedPage(active, "wiki-manual", "Manual Page", "CONCEPT",
                "# Manual Page\n\nBody.");
        long pageId = db().sql("SELECT id FROM knowledge_page WHERE knowledge_id = 'wiki-manual'")
                .query(Long.class).single();
        db().sql("UPDATE knowledge_page SET proposal_id = :proposal WHERE id = :id")
                .param("proposal", askId).param("id", pageId).update();
        String logicalPath = db().sql("SELECT markdown_path FROM knowledge_page WHERE id = :id")
                .param("id", pageId).query(String.class).single();
        Path vaultFile = active.root().resolve(logicalPath);
        Files.writeString(vaultFile, Files.readString(vaultFile) + "\n\nHand edit.\n");
        org.km.llmwiki.wiki.RepairProposalIngressResponse response = ingressService.createIngress(
                new org.km.llmwiki.wiki.CreateRepairProposalRequest("wiki-manual"));
        org.assertj.core.api.Assertions.assertThat(response.duplicate()).isFalse();
        org.assertj.core.api.Assertions.assertThat(response.proposal().status())
                .isEqualTo(org.km.llmwiki.wiki.KnowledgeProposalStatus.REVIEW);
        org.assertj.core.api.Assertions.assertThat(response.proposal().action())
                .isEqualTo(org.km.llmwiki.ai.LlmProposalAction.MERGE);
    }

    @Test
    void repairsDriftedPageThroughTheGovernedLoopAndClearsTheFinding() throws Exception {
        Fixture fixture = seedDriftedPageWithLineage();
        awaitEmbeddingProjectionTasks();

        mockMvc.perform(get("/api/v1/vault-lint/findings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.findings.length()").value(2))
                .andExpect(jsonPath("$.data.findings[0].finding.code").value("ORPHAN_PAGE"))
                .andExpect(jsonPath("$.data.findings[0].repairEligible").value(false))
                .andExpect(jsonPath("$.data.findings[1].finding.code").value("CANONICAL_CONTENT_INVALID"))
                .andExpect(jsonPath("$.data.findings[1].finding.knowledgeId").value(fixture.knowledgeId()))
                .andExpect(jsonPath("$.data.findings[1].repairEligible").value(true));

        MvcResult created = mockMvc.perform(post("/api/v1/repair/proposals")
                        .contentType("application/json")
                        .content("{\"knowledgeId\":\"" + fixture.knowledgeId() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.proposal.status").value("REVIEW"))
                .andExpect(jsonPath("$.data.proposal.action").value("MERGE"))
                .andExpect(jsonPath("$.data.proposal.targetReference").value("WIKI:" + fixture.knowledgeId()))
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andReturn();
        long repairId = ((Number) com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(),
                "$.data.proposal.id")).longValue();

        // Same finding state repeats deterministically instead of forking proposals.
        mockMvc.perform(post("/api/v1/repair/proposals")
                        .contentType("application/json")
                        .content("{\"knowledgeId\":\"" + fixture.knowledgeId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposal.id").value((int) repairId))
                .andExpect(jsonPath("$.data.duplicate").value(true));

        // The existing governed flow takes over unchanged: approve, draft, preview/diff,
        // explicit publish. Approval never publishes by itself.
        mockMvc.perform(patch("/api/v1/proposals/{id}/status", repairId)
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        MvcResult draft = mockMvc.perform(post("/api/v1/wiki-drafts")
                        .contentType("application/json")
                        .content("{\"proposalId\":" + repairId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andReturn();
        long draftId = ((Number) com.jayway.jsonpath.JsonPath.read(draft.getResponse().getContentAsString(),
                "$.data.id")).longValue();
        // Repair baseline pins the actual drifted bytes (not the DB hash): the human
        // reviews the complete current-file versus regenerated diff at preview time.
        mockMvc.perform(get("/api/v1/wiki-drafts/{id}", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseContentHash").value(fixture.driftedHash()))
                .andExpect(jsonPath("$.data.expectedContentHash").value(fixture.driftedHash()));
        // The human reviews the complete current-file versus regenerated diff: the
        // ungoverned hand edit is visible as removed content, never silently kept.
        mockMvc.perform(get("/api/v1/wiki-drafts/{id}/diff", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unifiedDiff").isString())
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getContentAsString()).contains("Hand edit"));

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getStatus()).isIn(200, 201));

        // Re-lint confirms the finding is gone; only the orphan observation remains, so a
        // repeat repair command meets a deterministic refusal instead of a stale replay.
        mockMvc.perform(get("/api/v1/vault-lint/findings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.findings.length()").value(1))
                .andExpect(jsonPath("$.data.findings[0].finding.code").value("ORPHAN_PAGE"));
        mockMvc.perform(post("/api/v1/repair/proposals")
                        .contentType("application/json")
                        .content("{\"knowledgeId\":\"" + fixture.knowledgeId() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("REPAIR_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.error.message").value("此診斷項目無法修復，僅可檢視"));
    }

    @Test
    void disappearedFindingIsStaleRatherThanRepaired() throws Exception {
        Workspace active = createWorkspace("active");
        activate(active.id());
        seedPublishedPage(active, "wiki-hub", "Hub Page", "CONCEPT",
                "# Hub Page\n\nSee [[Healthy Page]].");
        seedPublishedPage(active, "wiki-healthy", "Healthy Page", "CONCEPT",
                "# Healthy Page\n\nBack to [[Hub Page]].");
        String logicalPath = db().sql("SELECT markdown_path FROM knowledge_page WHERE knowledge_id = 'wiki-hub'")
                .query(String.class).single();
        Path vaultFile = active.root().resolve(logicalPath);
        String canonical = Files.readString(vaultFile);
        Files.writeString(vaultFile, canonical + "\n\nHand edit.\n");
        // The drifted hub contributes no outbound references (lint skips its link scan),
        // so the healthy page reads as orphan alongside the invalid finding.
        mockMvc.perform(get("/api/v1/vault-lint/findings"))
                .andExpect(jsonPath("$.data.findings.length()").value(2))
                .andExpect(jsonPath("$.data.findings[0].finding.code").value("ORPHAN_PAGE"))
                .andExpect(jsonPath("$.data.findings[1].finding.code").value("CANONICAL_CONTENT_INVALID"));

        // An external fix removes the finding before any repair command runs.
        Files.writeString(vaultFile, canonical);
        mockMvc.perform(get("/api/v1/vault-lint/findings"))
                .andExpect(jsonPath("$.data.findings.length()").value(0));
        mockMvc.perform(post("/api/v1/repair/proposals")
                        .contentType("application/json")
                        .content("{\"knowledgeId\":\"wiki-hub\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REPAIR_FINDING_STALE"))
                .andExpect(jsonPath("$.error.message").value("修復對象已變動，請重新整理後再試一次"));
    }

    @Test
    void ambiguousSemanticAndLineagelessFindingsRefuseRepairDeterministically() throws Exception {
        Workspace active = createWorkspace("active");
        activate(active.id());
        seedPublishedPage(active, "wiki-hub", "Hub Page", "CONCEPT",
                "# Hub Page\n\nSee [[Missing Page]].");
        seedPublishedPage(active, "wiki-lonely", "Lonely Page", "CONCEPT",
                "# Lonely Page\n\nNobody links here.");

        mockMvc.perform(post("/api/v1/repair/proposals")
                        .contentType("application/json")
                        .content("{\"knowledgeId\":\"wiki-hub\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("REPAIR_NOT_ELIGIBLE"));
        mockMvc.perform(get("/api/v1/vault-lint/findings"))
                .andExpect(jsonPath("$.data.findings.length()").value(3))
                .andExpect(jsonPath("$.data.findings[0].finding.knowledgeId").value("wiki-hub"))
                .andExpect(jsonPath("$.data.findings[0].finding.code").value("BROKEN_INTERNAL_LINK"))
                .andExpect(jsonPath("$.data.findings[0].repairEligible").value(false))
                .andExpect(jsonPath("$.data.findings[0].repairRefusalReason").value("AMBIGUOUS_TARGET"))
                .andExpect(jsonPath("$.data.findings[1].finding.code").value("ORPHAN_PAGE"))
                .andExpect(jsonPath("$.data.findings[1].finding.knowledgeId").value("wiki-hub"))
                .andExpect(jsonPath("$.data.findings[2].finding.code").value("ORPHAN_PAGE"))
                .andExpect(jsonPath("$.data.findings[2].finding.knowledgeId").value("wiki-lonely"))
                .andExpect(jsonPath("$.data.findings[2].repairEligible").value(false))
                .andExpect(jsonPath("$.data.findings[2].repairRefusalReason")
                        .value("SEMANTIC_JUDGMENT_REQUIRED"));
        mockMvc.perform(post("/api/v1/repair/proposals")
                        .contentType("application/json")
                        .content("{\"knowledgeId\":\"wiki-lonely\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("REPAIR_NOT_ELIGIBLE"));

        // No proposal row may exist for refused repairs.
        org.assertj.core.api.Assertions.assertThat(db()
                .sql("SELECT COUNT(*) FROM knowledge_proposal WHERE source_kind = 'REPAIR'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void unknownForeignAndMalformedIdentitiesFailClosed() throws Exception {
        Workspace active = createWorkspace("active");
        activate(active.id());
        seedPublishedPage(active, "wiki-local", "Local Page", "CONCEPT",
                "# Local Page\n\nBody.");
        Workspace other = createWorkspace("other");
        seedPublishedPage(other, "wiki-foreign", "Foreign Page", "CONCEPT",
                "# Foreign Page\n\nBody.");
        activate(active.id());

        mockMvc.perform(post("/api/v1/repair/proposals")
                        .contentType("application/json")
                        .content("{\"knowledgeId\":\"wiki-unknown\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WIKI_PAGE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("找不到指定的 Wiki 頁面"));
        mockMvc.perform(post("/api/v1/repair/proposals")
                        .contentType("application/json")
                        .content("{\"knowledgeId\":\"wiki-foreign\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WIKI_PAGE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("找不到指定的 Wiki 頁面"));
        mockMvc.perform(post("/api/v1/repair/proposals")
                        .contentType("application/json")
                        .content("{\"knowledgeId\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/repair/proposals")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void noActiveWorkspaceIsATyped404() throws Exception {
        mockMvc.perform(post("/api/v1/repair/proposals")
                        .contentType("application/json")
                        .content("{\"knowledgeId\":\"wiki-x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_WORKSPACE"))
                .andExpect(jsonPath("$.error.message").value("尚未開啟工作區"));
    }

    @Test
    void duplicateRepairInsertLosesTheRaceDeterministically() throws Exception {
        Workspace active = createWorkspace("active");
        activate(active.id());
        long documentId = insert("""
                INSERT INTO document (workspace_id, file_name, source_path, sha256, status, created_at, updated_at)
                VALUES (:ws, 'source.txt', 'source.txt', 'hash', 'PROCESSED', :now, :now)
                """, "ws", active.id(), "now", "2026-09-01T00:00:00Z");
        long chunkId = insert("""
                INSERT INTO source_chunk (document_id, chunk_no, content, normalized_content, content_hash,
                    created_at, updated_at)
                VALUES (:document, 1, 'chunk content', 'chunk content', 'chunk-hash', :now, :now)
                """, "document", documentId, "now", "2026-09-01T00:00:00Z");
        org.km.llmwiki.wiki.RepairProposalIngressRepository repository =
                new org.km.llmwiki.wiki.RepairProposalIngressRepository(dslContext);
        org.km.llmwiki.wiki.RepairPlan plan = new org.km.llmwiki.wiki.RepairPlan(
                "RESTORE_FROM_GOVERNED_LINEAGE", "v1", "wiki-x", "CANONICAL_CONTENT_INVALID",
                "hash", 99L, "{\"title\":\"T\",\"summary\":\"S\",\"pageType\":\"CONCEPT\"}",
                "WIKI:wiki-x", java.util.List.of(chunkId), "{\"repairKind\":\"x\"}", "dedup-1");
        long first = repository.insertRepairProposal(active.id(), plan, "v2");
        org.assertj.core.api.Assertions.assertThat(first).isPositive();
        org.assertj.core.api.Assertions.assertThat(
                        repository.findByRepairDedupHash(active.id(), "dedup-1"))
                .isPresent();
        org.assertj.core.api.Assertions.assertThat(
                        repository.findContractVersion(active.id(), first))
                .contains("v2");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> repository.insertRepairProposal(active.id(), plan, "v2"))
                .isInstanceOf(org.km.llmwiki.wiki.DuplicateRepairProposalException.class);
    }

    private record Fixture(String knowledgeId, Path vaultFile, String driftedHash) {
    }

    private record Workspace(long id, Path root) {
    }

    /**
     * Publishes a page through the governed Ask flow (giving it resolvable lineage),
     * then drifts the vault bytes so the finding is real.
     */
    private Fixture seedDriftedPageWithLineage() throws Exception {
        Workspace active = createWorkspace("active");
        activate(active.id());
        long documentId = insert("""
                INSERT INTO document (workspace_id, file_name, source_path, sha256, status, created_at, updated_at)
                VALUES (:ws, 'source.txt', 'source.txt', 'hash', 'PROCESSED', :now, :now)
                """, "ws", active.id(), "now", "2026-09-01T00:00:00Z");
        long chunkId = insert("""
                INSERT INTO source_chunk (document_id, chunk_no, content, normalized_content, content_hash,
                    created_at, updated_at)
                VALUES (:document, 1, 'chunk content', 'chunk content', 'chunk-hash', :now, :now)
                """, "document", documentId, "now", "2026-09-01T00:00:00Z");

        MvcResult ask = mockMvc.perform(post("/api/v1/ask/proposals")
                        .contentType("application/json").content("""
                                {"question": "transformer 核心是什麼？",
                                 "answerText": "Transformer 以 self-attention 為核心。",
                                 "provider": "openai-compatible",
                                 "model": "gpt-test",
                                 "citations": [{"evidenceId": "E1", "kind": "SOURCE", "sourceChunkId": %d}]}
                                """.formatted(chunkId)))
                .andExpect(status().isCreated())
                .andReturn();
        long askId = ((Number) com.jayway.jsonpath.JsonPath.read(ask.getResponse().getContentAsString(),
                "$.data.proposal.id")).longValue();
        mockMvc.perform(patch("/api/v1/proposals/{id}/status", askId)
                        .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());
        MvcResult draft = mockMvc.perform(post("/api/v1/wiki-drafts")
                        .contentType("application/json").content("{\"proposalId\":" + askId + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        long draftId = ((Number) com.jayway.jsonpath.JsonPath.read(draft.getResponse().getContentAsString(),
                "$.data.id")).longValue();
        MvcResult published = mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getStatus()).isIn(200, 201))
                .andReturn();
        String knowledgeId = com.jayway.jsonpath.JsonPath.read(
                published.getResponse().getContentAsString(), "$.data.knowledgeId");

        String logicalPath = db().sql("""
                        SELECT markdown_path FROM knowledge_page
                        WHERE workspace_id = :ws AND knowledge_id = :knowledgeId
                        """).param("ws", active.id()).param("knowledgeId", knowledgeId)
                .query(String.class).single();
        Path vaultFile = active.root().resolve(logicalPath);
        Files.writeString(vaultFile, Files.readString(vaultFile) + "\n\nHand edit.\n");
        String driftedHash = WikiContentHash.sha256(Files.readAllBytes(vaultFile));
        return new Fixture(knowledgeId, vaultFile, driftedHash);
    }

    private Workspace createWorkspace(String name) throws Exception {
        Path root = tempDir.resolve(name);
        Files.createDirectories(root.resolve("vault/concepts"));
        Files.createDirectories(root.resolve("inbox"));
        Files.createDirectories(root.resolve("archive"));
        Files.createDirectories(root.resolve("data"));
        long id = insert("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path, data_path, status,
                    created_at, updated_at)
                VALUES (:name, :root, :inbox, :archive, :vault, :data, 'ACTIVE', :now, :now)
                """, "name", name, "root", root.toString(), "inbox", root.resolve("inbox").toString(),
                "archive", root.resolve("archive").toString(), "vault", root.resolve("vault").toString(),
                "data", root.resolve("data").toString(), "now", "2026-09-01T00:00:00Z");
        return new Workspace(id, root);
    }

    private void activate(long workspaceId) {
        db().sql("UPDATE workspace SET status = 'INACTIVE'").update();
        db().sql("UPDATE workspace SET status = 'ACTIVE' WHERE id = :id")
                .param("id", workspaceId).update();
    }

    private void seedPublishedPage(Workspace workspace, String knowledgeId, String title,
                                   String pageType, String body) throws Exception {
        String stem = java.text.Normalizer.normalize(title.strip(), java.text.Normalizer.Form.NFC);
        stem = stem.toLowerCase(java.util.Locale.ROOT);
        stem = stem.replaceAll("\\s+", "-").replaceAll("[^\\p{L}\\p{N}\\-_]", "")
                .replaceAll("-{2,}", "-").replaceAll("^-+|-+$", "");
        String logicalPath = "vault/" + WikiPageType.valueOf(pageType).folderName() + "/" + stem + ".md";
        String markdown = """
                ---
                id: "%s"
                title: "%s"
                type: "%s"
                status: "PUBLISHED"
                ---

                %s""".formatted(knowledgeId, title, pageType, body);
        String hash = WikiContentHash.sha256(markdown);
        Files.writeString(workspace.root().resolve(logicalPath), markdown);
        insert("""
                INSERT INTO knowledge_page (workspace_id, knowledge_id, title, normalized_title, type,
                    markdown_path, status, content_hash, revision, created_at, updated_at)
                VALUES (:workspace, :knowledgeId, :title, :normalizedTitle, :type, :path,
                    'PUBLISHED', :hash, 1, :now, :now)
                """, "workspace", workspace.id(), "knowledgeId", knowledgeId, "title", title,
                "normalizedTitle", WikiTargetReference.normalizeTitle(title), "type", pageType,
                "path", logicalPath, "status", "PUBLISHED", "hash", hash, "now", "2026-09-01T00:00:00Z");
    }

    private long insert(String sql, Object... parameters) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        var statement = db().sql(sql);
        for (int index = 0; index < parameters.length; index += 2) {
            statement = statement.param((String) parameters[index], parameters[index + 1]);
        }
        statement.update(keyHolder);
        Number key = keyHolder.getKey();
        org.assertj.core.api.Assertions.assertThat(key).isNotNull();
        return key.longValue();
    }

    @Test
    void rejectedRepairCanBeProposedAgainInsteadOfForkingA500() throws Exception {
        Fixture fixture = seedDriftedPageWithLineage();
        awaitEmbeddingProjectionTasks();

        MvcResult first = mockMvc.perform(post("/api/v1/repair/proposals")
                        .contentType("application/json")
                        .content("{\"knowledgeId\":\"" + fixture.knowledgeId() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long firstId = ((Number) com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.data.proposal.id")).longValue();

        // The human rejects the repair proposal; the finding itself is unchanged.
        mockMvc.perform(patch("/api/v1/proposals/{id}/status", firstId)
                        .contentType("application/json").content("{\"status\":\"REJECTED\"}"))
                .andExpect(status().isOk());

        // A retry must create a fresh proposal (the rejected one no longer occupies the
        // dedup index), never an unexplainable server error replaying the old state.
        MvcResult retry = mockMvc.perform(post("/api/v1/repair/proposals")
                        .contentType("application/json")
                        .content("{\"knowledgeId\":\"" + fixture.knowledgeId() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andExpect(jsonPath("$.data.proposal.status").value("REVIEW"))
                .andReturn();
        long retryId = ((Number) com.jayway.jsonpath.JsonPath.read(
                retry.getResponse().getContentAsString(), "$.data.proposal.id")).longValue();
        org.assertj.core.api.Assertions.assertThat(retryId).isNotEqualTo(firstId);
    }

    @Test
    void unreadableVaultTargetRefusesRepairInsteadOfCreatingADeadProposal() throws Exception {
        Workspace active = createWorkspace("active");
        activate(active.id());
        seedPublishedPage(active, "wiki-gone", "Gone Page", "CONCEPT",
                "# Gone Page\n\nBody.");
        String logicalPath = db().sql("SELECT markdown_path FROM knowledge_page WHERE knowledge_id = 'wiki-gone'")
                .query(String.class).single();
        Files.deleteIfExists(active.root().resolve(logicalPath));

        // The missing file reports as canonical-content-invalid, but its bytes can never
        // be baselined, so the capability stays off and the command is typed-refused.
        mockMvc.perform(get("/api/v1/vault-lint/findings"))
                .andExpect(jsonPath("$.data.findings.length()").value(2))
                .andExpect(jsonPath("$.data.findings[1].finding.code").value("CANONICAL_CONTENT_INVALID"))
                .andExpect(jsonPath("$.data.findings[1].repairEligible").value(false))
                .andExpect(jsonPath("$.data.findings[1].repairRefusalReason").value("TARGET_NOT_READABLE"));
        mockMvc.perform(post("/api/v1/repair/proposals")
                        .contentType("application/json")
                        .content("{\"knowledgeId\":\"wiki-gone\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("REPAIR_NOT_ELIGIBLE"));
        org.assertj.core.api.Assertions.assertThat(db()
                .sql("SELECT COUNT(*) FROM knowledge_proposal WHERE source_kind = 'REPAIR'")
                .query(Long.class).single()).isZero();
    }
}
