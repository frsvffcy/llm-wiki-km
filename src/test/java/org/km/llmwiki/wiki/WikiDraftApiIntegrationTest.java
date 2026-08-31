package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.ai.LlmProposalAction;
import org.km.llmwiki.search.FtsSearchIndexRepository;
import org.km.llmwiki.search.PublishedWikiIndexingService;
import org.km.llmwiki.search.WikiIndexSyncResult;
import org.km.llmwiki.search.WikiIndexSyncStatus;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WikiDraftApiIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private WikiPublicationRepository publicationRepository;

    @MockitoSpyBean
    private AtomicWikiFileReplacer atomicFileReplacer;

    @MockitoSpyBean
    private WikiAtomicFilePublisher atomicFilePublisher;

    @MockitoSpyBean
    private FtsSearchIndexRepository ftsSearchIndexRepository;

    @Autowired
    private PublishedWikiIndexingService publishedWikiIndexingService;

    @Autowired
    private WikiMergePublishService mergePublishService;

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

    @Test
    void indexesCreatePublishAndKeepsNoOpIdempotent() throws Exception {
        Workspace workspace = createWorkspace("fts-create", "ACTIVE");
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "FTS Create Topic");
        long draftId = createDraft(proposal.id());

        String created = mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.outcome").value("CREATED"))
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"))
                .andReturn().getResponse().getContentAsString();
        String knowledgeId = json(created, "/data/knowledgeId");
        long pageId = Long.parseLong(json(created, "/data/knowledgePageId"));
        String contentHash = json(created, "/data/contentHash");

        assertThat(ftsSearch(workspace.id(), "Rendered content"))
                .extracting(org.km.llmwiki.search.SearchIndexMatch::stableId)
                .containsExactly(knowledgeId);
        assertThat(db().sql("SELECT COUNT(*) FROM search_index_identity "
                + "WHERE corpus = 'KNOWLEDGE' AND workspace_id = :workspace AND stable_id = :stableId")
                .param("workspace", workspace.id()).param("stableId", knowledgeId)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(row("""
                SELECT status, knowledge_id, content_hash, indexed_content_hash, failure_detail
                FROM knowledge_search_index_sync
                WHERE workspace_id = :workspace AND knowledge_page_id = :page
                """, "workspace", workspace.id(), "page", pageId))
                .containsEntry("status", "SYNCED")
                .containsEntry("knowledge_id", knowledgeId)
                .containsEntry("content_hash", contentHash)
                .containsEntry("indexed_content_hash", contentHash)
                .containsEntry("failure_detail", null);

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("NO_OP"));
        assertThat(ftsSearch(workspace.id(), "Rendered content"))
                .extracting(org.km.llmwiki.search.SearchIndexMatch::stableId)
                .containsExactly(knowledgeId);
        assertThat(db().sql("SELECT COUNT(*) FROM search_index_identity "
                + "WHERE corpus = 'KNOWLEDGE' AND workspace_id = :workspace")
                .param("workspace", workspace.id()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void updatesTheSameFtsIdentityForMergeAndReindexIsDeterministic() throws Exception {
        Workspace workspace = createWorkspace("fts-merge", "ACTIVE");
        Path target = workspace.root().resolve("vault/concepts/existing-topic.md");
        String baseline = "# Existing Topic\n\nBaseline\n";
        Files.writeString(target, baseline);
        insertKnowledgePage(workspace.id(), "existing-topic", "Existing Topic",
                WikiContentHash.sha256(Files.readAllBytes(target)));

        Proposal firstProposal = createProposal(workspace.id(), LlmProposalAction.MERGE, "wiki:existing-topic",
                KnowledgeProposalStatus.APPROVED, "First Merge");
        long firstDraftId = createDraft(firstProposal.id());
        String first = mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", firstDraftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("MERGED"))
                .andReturn().getResponse().getContentAsString();
        long pageId = Long.parseLong(json(first, "/data/knowledgePageId"));
        String knowledgeId = json(first, "/data/knowledgeId");
        long firstRowId = db().sql("SELECT fts_rowid FROM search_index_identity "
                + "WHERE corpus = 'KNOWLEDGE' AND workspace_id = :workspace AND stable_id = :stableId")
                .param("workspace", workspace.id()).param("stableId", knowledgeId)
                .query(Long.class).single();

        Proposal secondProposal = createProposal(workspace.id(), LlmProposalAction.MERGE, "wiki:existing-topic",
                KnowledgeProposalStatus.APPROVED, "Second Merge");
        db().sql("UPDATE knowledge_proposal SET normalized_data_json = "
                + "REPLACE(normalized_data_json, 'Rendered content', 'Revision two unique') "
                + "WHERE id = :id").param("id", secondProposal.id()).update();
        long secondDraftId = createDraft(secondProposal.id());
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", secondDraftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("MERGED"));

        assertThat(ftsSearch(workspace.id(), "Revision two unique"))
                .extracting(org.km.llmwiki.search.SearchIndexMatch::stableId)
                .containsExactly(knowledgeId);
        assertThat(ftsSearch(workspace.id(), "Rendered content")).isEmpty();
        assertThat(db().sql("SELECT COUNT(*) FROM search_index_identity "
                + "WHERE corpus = 'KNOWLEDGE' AND workspace_id = :workspace AND stable_id = :stableId")
                .param("workspace", workspace.id()).param("stableId", knowledgeId)
                .query(Integer.class).single()).isEqualTo(1);

        WikiIndexSyncResult firstReindex = publishedWikiIndexingService.reindex(workspace.id(), pageId);
        WikiIndexSyncResult secondReindex = publishedWikiIndexingService.reindex(workspace.id(), pageId);
        assertThat(firstReindex.status()).isEqualTo(WikiIndexSyncStatus.SYNCED);
        assertThat(secondReindex.status()).isEqualTo(WikiIndexSyncStatus.SYNCED);
        assertThat(db().sql("SELECT fts_rowid FROM search_index_identity "
                + "WHERE corpus = 'KNOWLEDGE' AND workspace_id = :workspace AND stable_id = :stableId")
                .param("workspace", workspace.id()).param("stableId", knowledgeId)
                .query(Long.class).single()).isEqualTo(firstRowId);
    }

    @Test
    void doesNotIndexConflictedOrFailedPublish() throws Exception {
        Workspace conflictWorkspace = createWorkspace("fts-conflict", "ACTIVE");
        Proposal conflictProposal = createProposal(conflictWorkspace.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "FTS Conflict Topic");
        long conflictDraftId = createDraft(conflictProposal.id());
        Files.writeString(conflictWorkspace.root().resolve("vault/concepts/fts-conflict-topic.md"),
                "manual content\n");
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", conflictDraftId))
                .andExpect(status().isConflict());
        assertThat(count("knowledge_fts")).isZero();
        assertThat(count("knowledge_search_index_sync")).isZero();

        Workspace failedWorkspace = createWorkspace("fts-failed", "INACTIVE");
        activate(failedWorkspace.id());
        Proposal failedProposal = createProposal(failedWorkspace.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "FTS Failed Topic");
        long failedDraftId = createDraft(failedProposal.id());
        doThrow(new IllegalStateException("simulated CREATE filesystem failure"))
                .when(atomicFilePublisher).commit(any());
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", failedDraftId))
                .andExpect(status().isInternalServerError());
        assertThat(count("knowledge_fts")).isZero();
        assertThat(count("knowledge_search_index_sync")).isZero();
        assertThat(count("knowledge_page")).isZero();
    }

    @Test
    void keepsSuccessfulVaultPublishWhenFtsFailsAndRepairsItLater() throws Exception {
        Workspace workspace = createWorkspace("fts-pending", "ACTIVE");
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "FTS Pending Topic");
        long draftId = createDraft(proposal.id());
        doThrow(new IllegalStateException("simulated FTS outage"))
                .when(ftsSearchIndexRepository).upsertKnowledge(any());

        String published = mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"))
                .andReturn().getResponse().getContentAsString();
        long pageId = Long.parseLong(json(published, "/data/knowledgePageId"));
        String knowledgeId = json(published, "/data/knowledgeId");
        Path target = workspace.root().resolve("vault/concepts/fts-pending-topic.md");
        assertThat(Files.exists(target)).isTrue();
        assertThat(count("knowledge_page")).isEqualTo(1);
        assertThat(count("knowledge_fts")).isZero();
        Map<String, Object> pendingLedger = row("""
                SELECT status, failure_detail, indexed_content_hash
                FROM knowledge_search_index_sync
                WHERE workspace_id = :workspace AND knowledge_page_id = :page
                """, "workspace", workspace.id(), "page", pageId);
        assertThat(pendingLedger).containsEntry("status", "INDEX_PENDING")
                .containsEntry("indexed_content_hash", null)
                .containsEntry("failure_detail", "Published Wiki FTS sync failed: IllegalStateException: simulated FTS outage");

        doCallRealMethod().when(ftsSearchIndexRepository).upsertKnowledge(any());
        WikiIndexSyncResult repaired = publishedWikiIndexingService.reindex(workspace.id(), pageId);
        assertThat(repaired.status()).isEqualTo(WikiIndexSyncStatus.SYNCED);
        assertThat(ftsSearch(workspace.id(), "Rendered content"))
                .extracting(org.km.llmwiki.search.SearchIndexMatch::stableId)
                .containsExactly(knowledgeId);
        assertThat(row("""
                SELECT status, indexed_content_hash, failure_detail
                FROM knowledge_search_index_sync
                WHERE workspace_id = :workspace AND knowledge_page_id = :page
                """, "workspace", workspace.id(), "page", pageId))
                .containsEntry("status", "SYNCED")
                .containsEntry("failure_detail", null)
                .containsEntry("indexed_content_hash", json(published, "/data/contentHash"));
    }

    @Test
    void failsClosedOnVaultOrDatabaseContentHashDriftWithoutOverwritingOldIndex() throws Exception {
        Workspace workspace = createWorkspace("fts-drift", "ACTIVE");
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "FTS Drift Topic");
        long draftId = createDraft(proposal.id());
        String published = mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long pageId = Long.parseLong(json(published, "/data/knowledgePageId"));
        String knowledgeId = json(published, "/data/knowledgeId");
        Path target = workspace.root().resolve("vault/concepts/fts-drift-topic.md");
        byte[] canonical = Files.readAllBytes(target);
        Files.writeString(target, "manual edit outside publish\n");

        WikiIndexSyncResult vaultDrift = publishedWikiIndexingService.reindex(workspace.id(), pageId);
        assertThat(vaultDrift.status()).isEqualTo(WikiIndexSyncStatus.DRIFT);
        assertThat(ftsSearch(workspace.id(), "Rendered content"))
                .extracting(org.km.llmwiki.search.SearchIndexMatch::stableId)
                .containsExactly(knowledgeId);
        assertThat(ftsSearch(workspace.id(), "manual edit outside publish")).isEmpty();
        assertThat(row("SELECT status FROM knowledge_search_index_sync "
                + "WHERE workspace_id = :workspace AND knowledge_page_id = :page",
                "workspace", workspace.id(), "page", pageId)).containsEntry("status", "DRIFT");

        Files.write(target, canonical);
        db().sql("UPDATE knowledge_page SET content_hash = :hash WHERE id = :id")
                .param("hash", "0".repeat(64)).param("id", pageId).update();
        WikiIndexSyncResult databaseDrift = publishedWikiIndexingService.reindex(workspace.id(), pageId);
        assertThat(databaseDrift.status()).isEqualTo(WikiIndexSyncStatus.DRIFT);
        assertThat(ftsSearch(workspace.id(), "Rendered content"))
                .extracting(org.km.llmwiki.search.SearchIndexMatch::stableId)
                .containsExactly(knowledgeId);
        assertThat(row("SELECT status, indexed_content_hash FROM knowledge_search_index_sync "
                + "WHERE workspace_id = :workspace AND knowledge_page_id = :page",
                "workspace", workspace.id(), "page", pageId))
                .containsEntry("status", "DRIFT")
                .containsEntry("indexed_content_hash", json(published, "/data/contentHash"));
    }

    @Test
    void isolatesPublishedWikiIndexAcrossWorkspaces() throws Exception {
        Workspace first = createWorkspace("fts-isolation-first", "ACTIVE");
        Proposal firstProposal = createProposal(first.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "Workspace Scoped Topic");
        long firstDraft = createDraft(firstProposal.id());
        String firstPublished = mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", firstDraft))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String firstKnowledgeId = json(firstPublished, "/data/knowledgeId");
        assertThat(ftsSearch(first.id(), "Rendered content"))
                .extracting(org.km.llmwiki.search.SearchIndexMatch::stableId)
                .containsExactly(firstKnowledgeId);

        Workspace second = createWorkspace("fts-isolation-second", "INACTIVE");
        activate(second.id());
        assertThat(ftsSearch(second.id(), "Rendered content")).isEmpty();
        assertThat(ftsSearch(first.id(), "Rendered content"))
                .extracting(org.km.llmwiki.search.SearchIndexMatch::stableId)
                .containsExactly(firstKnowledgeId);
    }

    @Test
    void explicitlyPublishesCreateWithAuditableMetadataAndReturnsSafeNoOpOnRepeat() throws Exception {
        Workspace workspace = createWorkspace("publish", "ACTIVE");
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "Published Topic");
        long draftId = createDraft(proposal.id());
        Path target = workspace.root().resolve("vault/concepts/published-topic.md");

        String created = mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.outcome").value("CREATED"))
                .andExpect(jsonPath("$.data.attemptId").isNumber())
                .andExpect(jsonPath("$.data.workspaceId").value(workspace.id()))
                .andExpect(jsonPath("$.data.proposalId").value(proposal.id()))
                .andExpect(jsonPath("$.data.draftId").value(draftId))
                .andExpect(jsonPath("$.data.targetPath").value("vault/concepts/published-topic.md"))
                .andExpect(jsonPath("$.data.revision").value(1))
                .andReturn().getResponse().getContentAsString();

        String published = Files.readString(target);
        String contentHash = WikiContentHash.sha256(Files.readAllBytes(target));
        String knowledgeId = json(created, "/data/knowledgeId");
        String publishedAt = json(created, "/data/publishedAt");
        long operationId = Long.parseLong(json(created, "/data/operationId"));
        long firstAttemptId = Long.parseLong(json(created, "/data/attemptId"));
        long knowledgePageId = Long.parseLong(json(created, "/data/knowledgePageId"));
        assertThat(published)
                .startsWith("---\nid: \"" + knowledgeId + "\"\n")
                .contains("title: \"Published Topic\"")
                .contains("type: \"CONCEPT\"")
                .contains("status: \"PUBLISHED\"")
                .contains("aliases: []")
                .contains("tags: []")
                .contains("sources:\n  - \"document:")
                .contains("created_at: \"" + publishedAt + "\"")
                .contains("updated_at: \"" + publishedAt + "\"")
                .contains("proposal_id: " + proposal.id())
                .contains("draft_id: " + draftId)
                .contains("revision: 1")
                .contains("# Published Topic")
                .contains("## Summary")
                .contains("## Evidence");
        assertThat(json(created, "/data/contentHash")).isEqualTo(contentHash);

        Map<String, Object> page = row("""
                SELECT knowledge_id, markdown_path, status, content_hash, revision, proposal_id, draft_id,
                    published_at, created_at, updated_at
                FROM knowledge_page WHERE id = :id
                """, "id", knowledgePageId);
        assertThat(page).containsEntry("knowledge_id", knowledgeId)
                .containsEntry("markdown_path", "vault/concepts/published-topic.md")
                .containsEntry("status", "PUBLISHED")
                .containsEntry("content_hash", contentHash)
                .containsEntry("revision", 1)
                .containsEntry("proposal_id", Math.toIntExact(proposal.id()))
                .containsEntry("draft_id", Math.toIntExact(draftId))
                .containsEntry("published_at", publishedAt)
                .containsEntry("created_at", publishedAt)
                .containsEntry("updated_at", publishedAt);
        Map<String, Object> draft = row("""
                SELECT status, published_path, published_content_hash, published_revision, published_at
                FROM wiki_draft WHERE id = :id
                """, "id", draftId);
        assertThat(draft).containsEntry("status", "PUBLISHED")
                .containsEntry("published_path", "vault/concepts/published-topic.md")
                .containsEntry("published_content_hash", contentHash)
                .containsEntry("published_revision", 1)
                .containsEntry("published_at", publishedAt);
        assertThat(row("SELECT status, knowledge_page_id FROM wiki_publish_operation WHERE id = :id",
                "id", operationId)).containsEntry("status", "COMPLETED")
                .containsEntry("knowledge_page_id", Math.toIntExact(knowledgePageId));

        String noOp = mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("NO_OP"))
                .andExpect(jsonPath("$.data.outcome").value("NO_OP"))
                .andExpect(jsonPath("$.data.attemptId").isNumber())
                .andExpect(jsonPath("$.data.operationId").value(operationId))
                .andExpect(jsonPath("$.data.knowledgePageId").value(knowledgePageId))
                .andExpect(jsonPath("$.data.contentHash").value(contentHash))
                .andReturn().getResponse().getContentAsString();
        long noOpAttemptId = Long.parseLong(json(noOp, "/data/attemptId"));
        assertThat(noOpAttemptId).isNotEqualTo(firstAttemptId);
        assertThat(json(noOp, "/data/publishedAt")).isEqualTo(publishedAt);
        assertThat(Files.readString(target)).isEqualTo(published);
        assertThat(count("knowledge_page")).isEqualTo(1);
        assertThat(count("wiki_publish_operation")).isEqualTo(1);
        assertThat(row("""
                SELECT COUNT(*) AS attempt_count, COUNT(DISTINCT idempotency_key) AS identity_count,
                    SUM(CASE WHEN result = 'PUBLISHED' THEN 1 ELSE 0 END) AS published_count,
                    SUM(CASE WHEN result = 'NO_OP' THEN 1 ELSE 0 END) AS no_op_count
                FROM wiki_publish_attempt WHERE draft_id = :id
                """, "id", draftId)).containsEntry("attempt_count", 2)
                .containsEntry("identity_count", 1)
                .containsEntry("published_count", 1)
                .containsEntry("no_op_count", 1);
        assertThat(row("""
                SELECT operation_id, action, target_path, after_content_hash, revision, result,
                    failure_category, failure_code, failure_stage, error_detail,
                    started_at IS NOT NULL AS has_started, finished_at IS NOT NULL AS has_finished
                FROM wiki_publish_attempt WHERE id = :id
                """, "id", firstAttemptId)).containsEntry("operation_id", Math.toIntExact(operationId))
                .containsEntry("action", "CREATE")
                .containsEntry("target_path", "vault/concepts/published-topic.md")
                .containsEntry("after_content_hash", contentHash)
                .containsEntry("revision", 1)
                .containsEntry("result", "PUBLISHED")
                .containsEntry("failure_category", null)
                .containsEntry("failure_code", null)
                .containsEntry("failure_stage", null)
                .containsEntry("error_detail", null)
                .containsEntry("has_started", 1)
                .containsEntry("has_finished", 1);

        Files.delete(target);
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_PUBLISHED_FILE_DRIFT"));
        assertThat(Files.exists(target)).isFalse();
        assertThat(count("knowledge_page")).isEqualTo(1);
        assertThat(count("wiki_publish_operation")).isEqualTo(1);
        assertThat(row("""
                SELECT operation_id, result, failure_category, failure_code, failure_stage, error_detail
                FROM wiki_publish_attempt WHERE draft_id = :id ORDER BY id DESC LIMIT 1
                """, "id", draftId)).containsEntry("operation_id", Math.toIntExact(operationId))
                .containsEntry("result", "FAILED")
                .containsEntry("failure_category", "RECONCILIATION")
                .containsEntry("failure_code", "PUBLISHED_FILE_DRIFT")
                .containsEntry("failure_stage", "RECONCILIATION");
    }

    @Test
    void rejectsNonReadyAndPublishesMergeWithStableIdentityAndRevisionMetadata() throws Exception {
        Workspace workspace = createWorkspace("publish-rejections", "ACTIVE");
        Proposal createProposal = createProposal(workspace.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "Invalidated Topic");
        long invalidatedDraftId = createDraft(createProposal.id());
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/invalidate", invalidatedDraftId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", invalidatedDraftId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_DRAFT_NOT_READY"));
        assertThat(Files.exists(workspace.root().resolve("vault/concepts/invalidated-topic.md"))).isFalse();

        Path mergeTarget = workspace.root().resolve("vault/concepts/existing-topic.md");
        String baseline = "# Existing Topic\n\nManual baseline\n";
        Files.writeString(mergeTarget, baseline);
        String beforeHash = WikiContentHash.sha256(Files.readAllBytes(mergeTarget));
        insertKnowledgePage(workspace.id(), "existing-topic", "Existing Topic", beforeHash);
        Proposal mergeProposal = createProposal(workspace.id(), LlmProposalAction.MERGE, "wiki:existing-topic",
                KnowledgeProposalStatus.APPROVED, "Merge Candidate");
        long mergeDraftId = createDraft(mergeProposal.id());

        String merged = mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", mergeDraftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("MERGED"))
                .andExpect(jsonPath("$.data.knowledgeId").value("existing-topic"))
                .andExpect(jsonPath("$.data.beforeHash").value(beforeHash))
                .andExpect(jsonPath("$.data.revision").value(2))
                .andReturn().getResponse().getContentAsString();
        String afterHash = WikiContentHash.sha256(Files.readAllBytes(mergeTarget));
        String publishedAt = json(merged, "/data/publishedAt");
        long operationId = Long.parseLong(json(merged, "/data/operationId"));
        long pageId = Long.parseLong(json(merged, "/data/knowledgePageId"));
        assertThat(json(merged, "/data/afterHash")).isEqualTo(afterHash);
        assertThat(Files.readString(mergeTarget))
                .contains("id: \"existing-topic\"")
                .contains("title: \"Existing Topic\"")
                .contains("created_at: \"2026-08-29T00:00:00Z\"")
                .contains("updated_at: \"" + publishedAt + "\"")
                .contains("revision: 2")
                .contains("# Existing Topic")
                .doesNotContain("# Merge Candidate");
        assertThat(row("""
                SELECT knowledge_id, title, markdown_path, status, content_hash, revision, proposal_id, draft_id,
                    created_at, updated_at, published_at
                FROM knowledge_page WHERE id = :id
                """, "id", pageId)).containsEntry("knowledge_id", "existing-topic")
                .containsEntry("title", "Existing Topic")
                .containsEntry("markdown_path", "vault/concepts/existing-topic.md")
                .containsEntry("status", "PUBLISHED")
                .containsEntry("content_hash", afterHash)
                .containsEntry("revision", 2)
                .containsEntry("proposal_id", Math.toIntExact(mergeProposal.id()))
                .containsEntry("draft_id", Math.toIntExact(mergeDraftId))
                .containsEntry("created_at", "2026-08-29T00:00:00Z")
                .containsEntry("updated_at", publishedAt)
                .containsEntry("published_at", publishedAt);
        assertThat(row("""
                SELECT action, before_content_hash, content_hash, revision, status, knowledge_page_id
                FROM wiki_publish_operation WHERE id = :id
                """, "id", operationId)).containsEntry("action", "MERGE")
                .containsEntry("before_content_hash", beforeHash)
                .containsEntry("content_hash", afterHash)
                .containsEntry("revision", 2)
                .containsEntry("status", "COMPLETED")
                .containsEntry("knowledge_page_id", Math.toIntExact(pageId));
        assertThat(row("""
                SELECT status, published_path, published_content_hash, published_revision, published_at
                FROM wiki_draft WHERE id = :id
                """, "id", mergeDraftId)).containsEntry("status", "PUBLISHED")
                .containsEntry("published_path", "vault/concepts/existing-topic.md")
                .containsEntry("published_content_hash", afterHash)
                .containsEntry("published_revision", 2)
                .containsEntry("published_at", publishedAt);
    }

    @Test
    void rejectsMergeHashMismatchBeforeAnyDatabaseOrFilesystemSideEffect() throws Exception {
        Workspace workspace = createWorkspace("merge-manual-edit", "ACTIVE");
        Path target = workspace.root().resolve("vault/concepts/existing-topic.md");
        String baseline = "# Existing Topic\r\n\r\nOriginal bytes\r\n";
        Files.write(target, baseline.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String expectedHash = WikiContentHash.sha256(Files.readAllBytes(target));
        insertKnowledgePage(workspace.id(), "existing-topic", "Existing Topic", expectedHash);
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.MERGE, "wiki:existing-topic",
                KnowledgeProposalStatus.APPROVED, "Merge Candidate");
        long draftId = createDraft(proposal.id());
        String manual = "# Existing Topic\n\nEdited manually in Obsidian\n";
        Files.writeString(target, manual);
        Map<String, Object> pageBefore = row("""
                SELECT content_hash, revision, proposal_id, draft_id, updated_at, published_at
                FROM knowledge_page WHERE knowledge_id = 'existing-topic'
                """);

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_OPTIMISTIC_LOCK_CONFLICT"));
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_OPTIMISTIC_LOCK_CONFLICT"));

        assertThat(Files.readString(target)).isEqualTo(manual);
        assertThat(count("wiki_publish_operation")).isZero();
        assertThat(row("""
                SELECT content_hash, revision, proposal_id, draft_id, updated_at, published_at
                FROM knowledge_page WHERE knowledge_id = 'existing-topic'
                """)).isEqualTo(pageBefore);
        assertThat(row("SELECT status, published_at FROM wiki_draft WHERE id = :id", "id", draftId))
                .containsEntry("status", "READY").containsEntry("published_at", null);
        assertThat(row("""
                SELECT COUNT(*) AS attempt_count, COUNT(DISTINCT idempotency_key) AS identity_count,
                    SUM(CASE WHEN result = 'CONFLICT' THEN 1 ELSE 0 END) AS conflict_count,
                    SUM(CASE WHEN operation_id IS NULL THEN 1 ELSE 0 END) AS no_operation_count,
                    MIN(failure_category) AS failure_category, MIN(failure_code) AS failure_code,
                    MIN(failure_stage) AS failure_stage
                FROM wiki_publish_attempt WHERE draft_id = :id
                """, "id", draftId)).containsEntry("attempt_count", 2)
                .containsEntry("identity_count", 1)
                .containsEntry("conflict_count", 2)
                .containsEntry("no_operation_count", 2)
                .containsEntry("failure_category", "CONFLICT")
                .containsEntry("failure_code", "OPTIMISTIC_LOCK_CONFLICT")
                .containsEntry("failure_stage", "TARGET_CHECK");
    }

    @Test
    void publishesMergeWhenDatabaseHashIsStaleButFilesystemMatchesDraftExpectedHash() throws Exception {
        Workspace workspace = createWorkspace("merge-filesystem-authority", "ACTIVE");
        Path target = workspace.root().resolve("vault/concepts/existing-topic.md");
        String baseline = "# Existing Topic\n\nFilesystem authority\n";
        Files.writeString(target, baseline);
        String expectedHash = WikiContentHash.sha256(Files.readAllBytes(target));
        insertKnowledgePage(workspace.id(), "existing-topic", "Existing Topic", expectedHash);
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.MERGE, "wiki:existing-topic",
                KnowledgeProposalStatus.APPROVED, "Filesystem Authority Merge");
        long draftId = createDraft(proposal.id());
        db().sql("UPDATE knowledge_page SET content_hash = :hash WHERE knowledge_id = 'existing-topic'")
                .param("hash", "0".repeat(64)).update();

        String response = mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("MERGED"))
                .andExpect(jsonPath("$.data.beforeHash").value(expectedHash))
                .andExpect(jsonPath("$.data.revision").value(2))
                .andReturn().getResponse().getContentAsString();

        String afterHash = WikiContentHash.sha256(Files.readAllBytes(target));
        assertThat(json(response, "/data/afterHash")).isEqualTo(afterHash);
        assertThat(row("SELECT content_hash, revision FROM knowledge_page WHERE knowledge_id = 'existing-topic'"))
                .containsEntry("content_hash", afterHash)
                .containsEntry("revision", 2);
        assertThat(row("SELECT status, published_content_hash, published_revision FROM wiki_draft WHERE id = :id",
                "id", draftId)).containsEntry("status", "PUBLISHED")
                .containsEntry("published_content_hash", afterHash)
                .containsEntry("published_revision", 2);
    }

    @Test
    void failsClosedWhenMergeTargetIsMissingOrDraftTargetIdentityIsTampered() throws Exception {
        Workspace workspace = createWorkspace("merge-target-failures", "ACTIVE");
        Path target = workspace.root().resolve("vault/concepts/existing-topic.md");
        Files.writeString(target, "# Existing Topic\n\nBaseline\n");
        String hash = WikiContentHash.sha256(Files.readAllBytes(target));
        insertKnowledgePage(workspace.id(), "existing-topic", "Existing Topic", hash);
        Proposal missingProposal = createProposal(workspace.id(), LlmProposalAction.MERGE, "wiki:existing-topic",
                KnowledgeProposalStatus.APPROVED, "Missing Candidate");
        long missingDraft = createDraft(missingProposal.id());
        Files.delete(target);

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", missingDraft))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_TARGET_MISSING"));
        assertThat(count("wiki_publish_operation")).isZero();

        Files.writeString(target, "# Existing Topic\n\nBaseline\n");
        Proposal tamperedProposal = createProposal(workspace.id(), LlmProposalAction.MERGE,
                "wiki:existing-topic", KnowledgeProposalStatus.APPROVED, "Tampered Candidate");
        long tamperedDraft = createDraft(tamperedProposal.id());
        db().sql("UPDATE wiki_draft SET target_path = 'vault/concepts/attacker.md' WHERE id = :id")
                .param("id", tamperedDraft).update();
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", tamperedDraft))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_TARGET_CONFLICT"));
        assertThat(Files.readString(target)).isEqualTo("# Existing Topic\n\nBaseline\n");
        assertThat(Files.exists(workspace.root().resolve("vault/concepts/attacker.md"))).isFalse();
        assertThat(count("wiki_publish_operation")).isZero();
    }

    @Test
    void isolatesMergePublishAndRejectsDirectNonMergeOrNonReadyCalls() throws Exception {
        Workspace active = createWorkspace("merge-isolation-active", "ACTIVE");
        Path target = active.root().resolve("vault/concepts/existing-topic.md");
        Files.writeString(target, "# Existing Topic\n\nBaseline\n");
        insertKnowledgePage(active.id(), "existing-topic", "Existing Topic",
                WikiContentHash.sha256(Files.readAllBytes(target)));
        Proposal mergeProposal = createProposal(active.id(), LlmProposalAction.MERGE, "wiki:existing-topic",
                KnowledgeProposalStatus.APPROVED, "Foreign Merge");
        long mergeDraft = createDraft(mergeProposal.id());
        Proposal createProposal = createProposal(active.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "Create Only");
        long createDraft = createDraft(createProposal.id());
        assertThatThrownBy(() -> mergePublishService.publish(createDraft))
                .isInstanceOfSatisfying(WikiPublishException.class, exception ->
                        assertThat(exception.reason()).isEqualTo(WikiPublishException.Reason.ACTION_NOT_MERGE));
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/invalidate", mergeDraft)).andExpect(status().isOk());
        assertThatThrownBy(() -> mergePublishService.publish(mergeDraft))
                .isInstanceOfSatisfying(WikiPublishException.class, exception ->
                        assertThat(exception.reason()).isEqualTo(WikiPublishException.Reason.DRAFT_NOT_READY));

        Workspace foreign = createWorkspace("merge-isolation-foreign", "INACTIVE");
        activate(foreign.id());
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", mergeDraft))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WIKI_DRAFT_NOT_FOUND"));
        assertThat(Files.readString(target)).isEqualTo("# Existing Topic\n\nBaseline\n");
        assertThat(count("wiki_publish_operation")).isZero();
    }

    @Test
    void safelyReturnsMergeNoOpAndNeverOverwritesLaterManualEdit() throws Exception {
        Workspace workspace = createWorkspace("merge-repeat", "ACTIVE");
        Path target = workspace.root().resolve("vault/concepts/existing-topic.md");
        Files.writeString(target, "# Existing Topic\n\nBaseline\n");
        insertKnowledgePage(workspace.id(), "existing-topic", "Existing Topic",
                WikiContentHash.sha256(Files.readAllBytes(target)));
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.MERGE, "wiki:existing-topic",
                KnowledgeProposalStatus.APPROVED, "Repeat Merge");
        long draftId = createDraft(proposal.id());
        String first = mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.outcome").value("MERGED"))
                .andReturn().getResponse().getContentAsString();
        String firstBytes = Files.readString(target);
        long operationId = Long.parseLong(json(first, "/data/operationId"));
        long pageId = Long.parseLong(json(first, "/data/knowledgePageId"));

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("NO_OP"))
                .andExpect(jsonPath("$.data.outcome").value("NO_OP"))
                .andExpect(jsonPath("$.data.operationId").value(operationId))
                .andExpect(jsonPath("$.data.revision").value(2));
        assertThat(Files.readString(target)).isEqualTo(firstBytes);
        assertThat(count("wiki_publish_operation")).isEqualTo(1);
        assertThat(row("SELECT revision FROM knowledge_page WHERE id = :id", "id", pageId))
                .containsEntry("revision", 2);
        assertThat(row("""
                SELECT COUNT(*) AS attempt_count, COUNT(DISTINCT idempotency_key) AS identity_count,
                    SUM(CASE WHEN result = 'PUBLISHED' THEN 1 ELSE 0 END) AS published_count,
                    SUM(CASE WHEN result = 'NO_OP' THEN 1 ELSE 0 END) AS no_op_count,
                    COUNT(DISTINCT revision) AS revision_count
                FROM wiki_publish_attempt WHERE draft_id = :id
                """, "id", draftId)).containsEntry("attempt_count", 2)
                .containsEntry("identity_count", 1)
                .containsEntry("published_count", 1)
                .containsEntry("no_op_count", 1)
                .containsEntry("revision_count", 1);

        String manual = "manual change after successful publish\n";
        Files.writeString(target, manual);
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_PUBLISHED_FILE_DRIFT"));
        assertThat(Files.readString(target)).isEqualTo(manual);
        assertThat(count("wiki_publish_operation")).isEqualTo(1);
        assertThat(row("SELECT revision FROM knowledge_page WHERE id = :id", "id", pageId))
                .containsEntry("revision", 2);
    }

    @Test
    void rollsBackMergeOperationWhenAtomicReplaceFails() throws Exception {
        Workspace workspace = createWorkspace("merge-atomic-failure", "ACTIVE");
        Path target = workspace.root().resolve("vault/concepts/existing-topic.md");
        String baseline = "# Existing Topic\n\nBaseline\n";
        Files.writeString(target, baseline);
        insertKnowledgePage(workspace.id(), "existing-topic", "Existing Topic",
                WikiContentHash.sha256(Files.readAllBytes(target)));
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.MERGE, "wiki:existing-topic",
                KnowledgeProposalStatus.APPROVED, "Atomic Failure");
        long draftId = createDraft(proposal.id());
        doThrow(new java.io.IOException("simulated atomic replace failure"))
                .when(atomicFileReplacer).replace(any(), any());

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_FILESYSTEM_FAILURE"));

        assertThat(Files.readString(target)).isEqualTo(baseline);
        assertThat(row("SELECT status FROM wiki_publish_operation WHERE draft_id = :id", "id", draftId))
                .containsEntry("status", "ROLLED_BACK");
        assertThat(row("SELECT status FROM wiki_draft WHERE id = :id", "id", draftId))
                .containsEntry("status", "READY");
        assertThat(row("SELECT revision, content_hash FROM knowledge_page WHERE knowledge_id = 'existing-topic'"))
                .containsEntry("revision", 1)
                .containsEntry("content_hash", WikiContentHash.sha256(baseline));
    }

    @Test
    void restoresMergeTargetWhenAtomicReplacerThrowsAfterMovingFile() throws Exception {
        Workspace workspace = createWorkspace("merge-post-move-failure", "ACTIVE");
        Path target = workspace.root().resolve("vault/concepts/existing-topic.md");
        byte[] baseline = ("\ufeff# Existing Topic\r\n\r\nOriginal bytes\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(target, baseline);
        String hash = WikiContentHash.sha256(baseline);
        insertKnowledgePage(workspace.id(), "existing-topic", "Existing Topic", hash);
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.MERGE, "wiki:existing-topic",
                KnowledgeProposalStatus.APPROVED, "Post Move Failure");
        long draftId = createDraft(proposal.id());
        doAnswer(invocation -> {
            Path staged = invocation.getArgument(0);
            Path destination = invocation.getArgument(1);
            Files.move(staged, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            throw new IllegalStateException("simulated failure after atomic move");
        }).when(atomicFileReplacer).replace(any(), any());

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_FILESYSTEM_FAILURE"));

        assertThat(Files.readAllBytes(target)).containsExactly(baseline);
        assertThat(row("SELECT status FROM wiki_publish_operation WHERE draft_id = :id", "id", draftId))
                .containsEntry("status", "ROLLED_BACK");
        assertThat(row("SELECT status FROM wiki_draft WHERE id = :id", "id", draftId))
                .containsEntry("status", "READY");
        assertThat(row("SELECT revision, content_hash FROM knowledge_page WHERE knowledge_id = 'existing-topic'"))
                .containsEntry("revision", 1).containsEntry("content_hash", hash);
    }

    @Test
    void recordsReconciliationWhenAtomicReplacerDriftsTargetAfterMovingFile() throws Exception {
        Workspace workspace = createWorkspace("merge-post-move-drift", "ACTIVE");
        Path target = workspace.root().resolve("vault/concepts/existing-topic.md");
        String baseline = "# Existing Topic\n\nBaseline\n";
        Files.writeString(target, baseline);
        String hash = WikiContentHash.sha256(Files.readAllBytes(target));
        insertKnowledgePage(workspace.id(), "existing-topic", "Existing Topic", hash);
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.MERGE, "wiki:existing-topic",
                KnowledgeProposalStatus.APPROVED, "Post Move Drift");
        long draftId = createDraft(proposal.id());
        doAnswer(invocation -> {
            Path staged = invocation.getArgument(0);
            Path destination = invocation.getArgument(1);
            Files.move(staged, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(destination, "external edit after atomic move\n");
            throw new java.io.IOException("simulated failure after external drift");
        }).when(atomicFileReplacer).replace(any(), any());

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_RECONCILIATION_REQUIRED"));

        assertThat(Files.readString(target)).isEqualTo("external edit after atomic move\n");
        assertThat(row("SELECT status FROM wiki_publish_operation WHERE draft_id = :id", "id", draftId))
                .containsEntry("status", "RECONCILIATION_REQUIRED");
        assertThat(row("SELECT status FROM wiki_draft WHERE id = :id", "id", draftId))
                .containsEntry("status", "READY");
        assertThat(row("SELECT revision, content_hash FROM knowledge_page WHERE knowledge_id = 'existing-topic'"))
                .containsEntry("revision", 1).containsEntry("content_hash", hash);
    }

    @Test
    void restoresExactMergeBytesWhenDatabaseFinalizationFails() throws Exception {
        Workspace workspace = createWorkspace("merge-db-compensation", "ACTIVE");
        Path target = workspace.root().resolve("vault/concepts/existing-topic.md");
        byte[] baseline = ("\ufeff# Existing Topic\r\n\r\nExact CRLF bytes\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(target, baseline);
        String hash = WikiContentHash.sha256(baseline);
        insertKnowledgePage(workspace.id(), "existing-topic", "Existing Topic", hash);
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.MERGE, "wiki:existing-topic",
                KnowledgeProposalStatus.APPROVED, "DB Failure");
        long draftId = createDraft(proposal.id());
        doThrow(new IllegalStateException("simulated MERGE DB failure")).doCallRealMethod()
                .when(publicationRepository).updateKnowledgePageForMerge(any(), any(), anyLong(), anyString());

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_METADATA_FAILURE"));

        assertThat(Files.readAllBytes(target)).containsExactly(baseline);
        assertThat(row("SELECT status FROM wiki_publish_operation WHERE draft_id = :id", "id", draftId))
                .containsEntry("status", "ROLLED_BACK");
        assertThat(row("SELECT status FROM wiki_draft WHERE id = :id", "id", draftId))
                .containsEntry("status", "READY");
        assertThat(row("SELECT revision, content_hash FROM knowledge_page WHERE knowledge_id = 'existing-topic'"))
                .containsEntry("revision", 1).containsEntry("content_hash", hash);

        long operationId = ((Number) row("""
                SELECT id FROM wiki_publish_operation WHERE draft_id = :id
                """, "id", draftId).get("id")).longValue();
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.outcome").value("MERGED"))
                .andExpect(jsonPath("$.data.operationId").value(operationId))
                .andExpect(jsonPath("$.data.revision").value(2));
        String recovered = Files.readString(target);
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("NO_OP"))
                .andExpect(jsonPath("$.data.operationId").value(operationId))
                .andExpect(jsonPath("$.data.revision").value(2));

        assertThat(Files.readString(target)).isEqualTo(recovered);
        assertThat(count("wiki_publish_operation")).isEqualTo(1);
        assertThat(row("SELECT status, revision FROM wiki_publish_operation WHERE id = :id", "id", operationId))
                .containsEntry("status", "COMPLETED").containsEntry("revision", 2);
        assertThat(row("SELECT revision FROM knowledge_page WHERE knowledge_id = 'existing-topic'"))
                .containsEntry("revision", 2);
        assertThat(row("""
                SELECT COUNT(*) AS attempt_count, COUNT(DISTINCT operation_id) AS operation_count,
                    COUNT(DISTINCT revision) AS revision_count,
                    SUM(CASE WHEN result = 'FAILED' AND failure_category = 'DATABASE'
                        AND failure_stage = 'DATABASE_FINALIZATION' THEN 1 ELSE 0 END) AS database_failure_count,
                    SUM(CASE WHEN result = 'PUBLISHED' THEN 1 ELSE 0 END) AS published_count,
                    SUM(CASE WHEN result = 'NO_OP' THEN 1 ELSE 0 END) AS no_op_count
                FROM wiki_publish_attempt WHERE draft_id = :id
                """, "id", draftId)).containsEntry("attempt_count", 3)
                .containsEntry("operation_count", 1)
                .containsEntry("revision_count", 1)
                .containsEntry("database_failure_count", 1)
                .containsEntry("published_count", 1)
                .containsEntry("no_op_count", 1);
    }

    @Test
    void recordsMergeReconciliationWhenDbFailsAfterExternalFileDrift() throws Exception {
        Workspace workspace = createWorkspace("merge-db-reconciliation", "ACTIVE");
        Path target = workspace.root().resolve("vault/concepts/existing-topic.md");
        String baseline = "# Existing Topic\n\nBaseline\n";
        Files.writeString(target, baseline);
        String hash = WikiContentHash.sha256(Files.readAllBytes(target));
        insertKnowledgePage(workspace.id(), "existing-topic", "Existing Topic", hash);
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.MERGE, "wiki:existing-topic",
                KnowledgeProposalStatus.APPROVED, "Reconciliation");
        long draftId = createDraft(proposal.id());
        doAnswer(invocation -> {
            Files.writeString(target, "external edit during MERGE DB finalization\n");
            throw new IllegalStateException("simulated MERGE DB failure after drift");
        }).when(publicationRepository).updateKnowledgePageForMerge(any(), any(), anyLong(), anyString());

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_RECONCILIATION_REQUIRED"));

        assertThat(Files.readString(target)).isEqualTo("external edit during MERGE DB finalization\n");
        assertThat(row("SELECT status FROM wiki_publish_operation WHERE draft_id = :id", "id", draftId))
                .containsEntry("status", "RECONCILIATION_REQUIRED");
        assertThat(row("SELECT status FROM wiki_draft WHERE id = :id", "id", draftId))
                .containsEntry("status", "READY");
        assertThat(row("SELECT revision, content_hash FROM knowledge_page WHERE knowledge_id = 'existing-topic'"))
                .containsEntry("revision", 1).containsEntry("content_hash", hash);
    }

    @Test
    void protectsManualOrObsidianTargetThatAppearsAfterDraftCreation() throws Exception {
        Workspace workspace = createWorkspace("manual-conflict", "ACTIVE");
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "Manual Topic");
        long draftId = createDraft(proposal.id());
        Path target = workspace.root().resolve("vault/concepts/manual-topic.md");
        String manualContent = "# Manual Obsidian page\n\nNever overwrite me.\n";
        Files.writeString(target, manualContent);

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_TARGET_CONFLICT"));

        assertThat(Files.readString(target)).isEqualTo(manualContent);
        assertThat(count("knowledge_page")).isZero();
        assertThat(row("SELECT status FROM wiki_publish_operation WHERE draft_id = :id", "id", draftId))
                .containsEntry("status", "ROLLED_BACK");
    }

    @Test
    void isolatesPublishByActiveWorkspaceAndRejectsInvalidProposalOrTamperedCanonicalPath() throws Exception {
        Workspace active = createWorkspace("publish-active", "ACTIVE");
        Proposal activeProposal = createProposal(active.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "Active Publish Topic");
        long foreignDraftId = createDraft(activeProposal.id());
        Workspace foreign = createWorkspace("publish-foreign", "INACTIVE");
        activate(foreign.id());

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", foreignDraftId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WIKI_DRAFT_NOT_FOUND"));
        assertThat(Files.exists(active.root().resolve("vault/concepts/active-publish-topic.md"))).isFalse();

        activate(active.id());
        Proposal invalidProposal = createProposal(active.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "Invalid Proposal Topic");
        long invalidProposalDraft = createDraft(invalidProposal.id());
        db().sql("UPDATE knowledge_proposal SET status = 'REVIEW' WHERE id = :id")
                .param("id", invalidProposal.id()).update();
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", invalidProposalDraft))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_PROPOSAL_INVALID"));

        Proposal tamperedProposal = createProposal(active.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "Canonical Topic");
        long tamperedDraft = createDraft(tamperedProposal.id());
        db().sql("UPDATE wiki_draft SET target_path = 'vault/concepts/attacker-path.md' WHERE id = :id")
                .param("id", tamperedDraft).update();
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", tamperedDraft))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_TARGET_CONFLICT"));
        assertThat(Files.exists(active.root().resolve("vault/concepts/attacker-path.md"))).isFalse();
        assertThat(Files.exists(active.root().resolve("vault/concepts/canonical-topic.md"))).isFalse();
        assertThat(count("wiki_publish_operation")).isZero();
    }

    @Test
    void recoversCreateFromVerifiedAfterHashWithSameOperationAndAuditsEveryAttempt() throws Exception {
        Workspace workspace = createWorkspace("db-compensation", "ACTIVE");
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "Compensated Topic");
        long draftId = createDraft(proposal.id());
        Path target = workspace.root().resolve("vault/concepts/compensated-topic.md");
        AtomicReference<String> committedContent = new AtomicReference<>();
        doAnswer(invocation -> {
            committedContent.set(Files.readString(target));
            throw new IllegalStateException("simulated DB finalization failure");
        }).doCallRealMethod()
                .when(publicationRepository).insertKnowledgePage(any(), any(), anyString());

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_METADATA_FAILURE"));

        assertThat(Files.exists(target)).isFalse();
        assertThat(count("knowledge_page")).isZero();
        assertThat(row("SELECT status, failure_detail FROM wiki_publish_operation WHERE draft_id = :id",
                "id", draftId)).containsEntry("status", "ROLLED_BACK");
        assertThat(row("SELECT status FROM wiki_draft WHERE id = :id", "id", draftId))
                .containsEntry("status", "READY");

        Map<String, Object> failedOperation = row("""
                SELECT id, content_hash, revision FROM wiki_publish_operation WHERE draft_id = :id
                """, "id", draftId);
        long operationId = ((Number) failedOperation.get("id")).longValue();
        assertThat(committedContent.get()).isNotBlank();
        assertThat(WikiContentHash.sha256(committedContent.get()))
                .isEqualTo(failedOperation.get("content_hash"));

        // Models a retry after the atomic CREATE became visible but DB finalization was not durable.
        Files.writeString(target, committedContent.get());
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.outcome").value("CREATED"))
                .andExpect(jsonPath("$.data.operationId").value(operationId))
                .andExpect(jsonPath("$.data.revision").value(1));
        String recovered = Files.readString(target);
        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("NO_OP"))
                .andExpect(jsonPath("$.data.operationId").value(operationId))
                .andExpect(jsonPath("$.data.revision").value(1));

        assertThat(Files.readString(target)).isEqualTo(recovered);
        assertThat(count("knowledge_page")).isEqualTo(1);
        assertThat(count("wiki_publish_operation")).isEqualTo(1);
        assertThat(row("SELECT status, revision FROM wiki_publish_operation WHERE id = :id", "id", operationId))
                .containsEntry("status", "COMPLETED").containsEntry("revision", 1);
        assertThat(row("""
                SELECT COUNT(*) AS attempt_count, COUNT(DISTINCT idempotency_key) AS identity_count,
                    COUNT(DISTINCT operation_id) AS operation_count, COUNT(DISTINCT revision) AS revision_count,
                    SUM(CASE WHEN result = 'FAILED' AND failure_category = 'DATABASE'
                        AND failure_code = 'METADATA_FAILURE'
                        AND failure_stage = 'DATABASE_FINALIZATION'
                        AND error_detail IS NOT NULL THEN 1 ELSE 0 END) AS audited_failure_count,
                    SUM(CASE WHEN result = 'PUBLISHED' THEN 1 ELSE 0 END) AS published_count,
                    SUM(CASE WHEN result = 'NO_OP' THEN 1 ELSE 0 END) AS no_op_count
                FROM wiki_publish_attempt WHERE draft_id = :id
                """, "id", draftId)).containsEntry("attempt_count", 3)
                .containsEntry("identity_count", 1)
                .containsEntry("operation_count", 1)
                .containsEntry("revision_count", 1)
                .containsEntry("audited_failure_count", 1)
                .containsEntry("published_count", 1)
                .containsEntry("no_op_count", 1);
    }

    @Test
    void recordsReconciliationWhenDatabaseFailsAndFinalFileNoLongerMatches() throws Exception {
        Workspace workspace = createWorkspace("db-reconciliation", "ACTIVE");
        Proposal proposal = createProposal(workspace.id(), LlmProposalAction.CREATE, null,
                KnowledgeProposalStatus.APPROVED, "Reconciliation Topic");
        long draftId = createDraft(proposal.id());
        Path target = workspace.root().resolve("vault/concepts/reconciliation-topic.md");
        doAnswer(invocation -> {
            Files.writeString(target, "externally changed during DB finalization\n");
            throw new IllegalStateException("simulated DB failure after external file drift");
        }).when(publicationRepository).insertKnowledgePage(any(), any(), anyString());

        mockMvc.perform(post("/api/v1/wiki-drafts/{id}/publish", draftId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("WIKI_PUBLISH_RECONCILIATION_REQUIRED"));

        assertThat(Files.readString(target)).isEqualTo("externally changed during DB finalization\n");
        assertThat(count("knowledge_page")).isZero();
        assertThat(row("SELECT status, failure_detail FROM wiki_publish_operation WHERE draft_id = :id",
                "id", draftId)).containsEntry("status", "RECONCILIATION_REQUIRED");
        assertThat(row("SELECT status FROM wiki_draft WHERE id = :id", "id", draftId))
                .containsEntry("status", "READY");
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

    private java.util.List<org.km.llmwiki.search.SearchIndexMatch> ftsSearch(long workspaceId, String query) {
        return ftsSearchIndexRepository.matchKnowledge(workspaceId, query);
    }

    private Map<String, Object> row(String sql, Object... parameters) {
        var statement = db().sql(sql);
        for (int index = 0; index < parameters.length; index += 2) {
            statement = statement.param((String) parameters[index], parameters[index + 1]);
        }
        return statement.query().singleRow();
    }

    private static String json(String body, String pointer) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).at(pointer).asText();
    }

    private record Workspace(long id, Path root) {
    }

    private record Proposal(long id) {
    }
}
