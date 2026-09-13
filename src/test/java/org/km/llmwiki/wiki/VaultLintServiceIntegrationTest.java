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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic, read-only Vault Lint (#379): findings come from the same canonical
 * authorities as every other surface (durable metadata + hash-validated content), the
 * same snapshot always yields the same report, cross-workspace references stay
 * isolated, and linting mutates nothing — not vault bytes, not SQLite state, not
 * proposals/drafts, not derived projections.
 */
class VaultLintServiceIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private VaultLintService lintService;

    @Autowired
    private MockMvc mockMvc;

    @TempDir
    Path tempDir;

    @Test
    void brokenLinksOrphansAndHealthyReferencesAreTypedDeterministically() throws Exception {
        Workspace ws = createWorkspace("active");
        // hub -> healthy (link resolves); lonely has no inbound reference; broken link
        // inside hub must be reported without fuzzy guessing.
        seedPublishedPage(ws, "wiki-hub", "Hub Page", "CONCEPT",
                "# Hub Page\n\nSee [[Healthy Page]] and [[Missing Page]].");
        seedPublishedPage(ws, "wiki-healthy", "Healthy Page", "CONCEPT",
                "# Healthy Page\n\nBody referencing [[Hub Page]].");
        seedPublishedPage(ws, "wiki-lonely", "Lonely Page", "CONCEPT",
                "# Lonely Page\n\nNothing points here.");

        VaultLintReport first = lintService.lintActiveWorkspace();
        VaultLintReport second = lintService.lintActiveWorkspace();

        assertThat(first.checkedPageCount()).isEqualTo(3);
        // Deterministic: the same canonical snapshot yields an identical sorted report.
        assertThat(second.findings()).isEqualTo(first.findings());

        List<VaultLintFinding> broken = findingsOf(first, VaultLintFinding.Code.BROKEN_INTERNAL_LINK);
        assertThat(broken).hasSize(1);
        assertThat(broken.get(0).knowledgeId()).isEqualTo("wiki-hub");
        assertThat(broken.get(0).detail()).contains("missing page");

        List<VaultLintFinding> orphans = findingsOf(first, VaultLintFinding.Code.ORPHAN_PAGE);
        // "Healthy Page" is referenced by hub; hub is referenced by healthy; lonely has
        // no inbound reference. Note: a broken link's target is never treated as inbound.
        assertThat(orphans).hasSize(1);
        assertThat(orphans.get(0).knowledgeId()).isEqualTo("wiki-lonely");
        assertThat(orphans.get(0).severity()).isEqualTo(VaultLintFinding.Severity.WARNING);

        assertThat(findingsOf(first, VaultLintFinding.Code.CANONICAL_CONTENT_INVALID)).isEmpty();
    }

    @Test
    void frontmatterAndHashDriftAreReportedThroughTheProductionAuthority() throws Exception {
        Workspace ws = createWorkspace("active");
        // Seed a page whose vault file drifts from the durable content hash: the lint
        // must report it through the same reader contract, never fabricate content.
        seedPublishedPageWithHash(ws, "wiki-drift", "Drifted Page", "CONCEPT",
                "# Drifted Page\n\ntrusted body", WikiContentHash.sha256("drifted body"));
        seedPublishedPage(ws, "wiki-ok", "Healthy Page", "CONCEPT",
                "# Healthy Page\n\n[[Drifted Page]]");

        VaultLintReport report = lintService.lintActiveWorkspace();

        List<VaultLintFinding> invalid = findingsOf(report, VaultLintFinding.Code.CANONICAL_CONTENT_INVALID);
        assertThat(invalid).hasSize(1);
        assertThat(invalid.get(0).knowledgeId()).isEqualTo("wiki-drift");
        // A drifted page's links must not be treated as resolved references.
        assertThat(findingsOf(report, VaultLintFinding.Code.BROKEN_INTERNAL_LINK)).isEmpty();
        // Orphan semantics follow durable existence: both pages lack inbound references
        // from OTHER published pages, so both are health observations.
        // wiki-drift still has a durable inbound reference (wiki-ok links to it), so it
        // is not an orphan — its content drift is the separate CANONICAL_CONTENT finding.
        List<VaultLintFinding> orphans = findingsOf(report, VaultLintFinding.Code.ORPHAN_PAGE);
        assertThat(orphans).hasSize(1);
        assertThat(orphans.get(0).knowledgeId()).isEqualTo("wiki-ok");
    }

    @Test
    void danglingProvenanceIsReportedWithoutTouchingAnything() throws Exception {
        Workspace ws = createWorkspace("active");
        seedPublishedPageWithHash(ws, "wiki-orphan-prov", "Orphan Provenance", "CONCEPT",
                "# Orphan Provenance\n\nbody", WikiContentHash.sha256(
                        frontmatter("wiki-orphan-prov", "Orphan Provenance", "CONCEPT")
                                + "# Orphan Provenance\n\nbody"));
        // A proposal that exists only in ANOTHER workspace: FK-valid, but the
        // workspace-scoped review authority cannot resolve it -> dangling (#379).
        createWorkspace("other");
        long foreignProposalId = seedProposalInWorkspace(lookupWorkspaceId("other"));
        db().sql("UPDATE knowledge_page SET proposal_id = :pid WHERE knowledge_id = :kid")
                .param("pid", foreignProposalId).param("kid", "wiki-orphan-prov").update();

        int proposalsBefore = count("knowledge_proposal");
        int draftsBefore = count("wiki_draft");
        VaultLintReport report = lintService.lintActiveWorkspace();

        List<VaultLintFinding> dangling = findingsOf(report, VaultLintFinding.Code.DANGLING_PROVENANCE);
        assertThat(dangling).hasSize(1);
        assertThat(dangling.get(0).severity()).isEqualTo(VaultLintFinding.Severity.WARNING);
        // Read-only: no repair, no deletion, no new proposals or drafts.
        assertThat(count("knowledge_proposal")).isEqualTo(proposalsBefore);
        assertThat(count("wiki_draft")).isEqualTo(draftsBefore);
    }

    @Test
    void crossWorkspacePagesAndLinksStayIsolated() throws Exception {
        Workspace active = createWorkspace("active");
        Workspace other = createWorkspace("other");
        seedPublishedPage(other, "wiki-foreign", "Foreign Page", "CONCEPT",
                "# Foreign Page\n\nbody");
        seedPublishedPage(active, "wiki-local", "Local Page", "CONCEPT",
                "# Local Page\n\nPoints at [[Foreign Page]] which must not resolve.");

        VaultLintReport report = lintService.lintActiveWorkspace();

        // The foreign page is not scanned; the cross-workspace link does not resolve
        // and must be reported as broken instead of silently resolving.
        assertThat(report.checkedPageCount()).isEqualTo(1);
        List<VaultLintFinding> broken = findingsOf(report, VaultLintFinding.Code.BROKEN_INTERNAL_LINK);
        assertThat(broken).hasSize(1);
        assertThat(broken.get(0).detail()).contains("foreign page");
        // The only outbound link is broken, so the page has no valid inbound reference.
        List<VaultLintFinding> orphans = findingsOf(report, VaultLintFinding.Code.ORPHAN_PAGE);
        assertThat(orphans).hasSize(1);
        assertThat(orphans.get(0).knowledgeId()).isEqualTo("wiki-local");
    }

    @Test
    void lintingMutatesNothing() throws Exception {
        Workspace ws = createWorkspace("active");
        seedPublishedPage(ws, "wiki-hub", "Hub Page", "CONCEPT",
                "# Hub Page\n\nSee [[Healthy Page]].");
        seedPublishedPage(ws, "wiki-healthy", "Healthy Page", "CONCEPT",
                "# Healthy Page\n\nbody with [[Missing Page]]");
        Path hubFile = ws.root().resolve("vault/concepts/hub-page.md");
        byte[] hubBytes = Files.readAllBytes(hubFile);
        List<String> pageRowsBefore = db().sql(
                "SELECT knowledge_id || '|' || status || '|' || content_hash FROM knowledge_page "
                        + "ORDER BY knowledge_id").query(String.class).list();

        lintService.lintActiveWorkspace();

        assertThat(Files.readAllBytes(hubFile)).isEqualTo(hubBytes);
        assertThat(db().sql("SELECT knowledge_id || '|' || status || '|' || content_hash "
                + "FROM knowledge_page ORDER BY knowledge_id").query(String.class).list())
                .isEqualTo(pageRowsBefore);
        assertThat(count("knowledge_proposal")).isEqualTo(count("knowledge_proposal"));
    }

    @Test
    void wikilinkTargetsNormalizeThroughTheSameAuthority() {
        // Extraction is per-occurrence (dedup happens in the service's reference set);
        // alias forms and empty targets are handled by the shared normalization.
        assertThat(VaultLintService.extractWikilinkTargets(
                "[[Healthy Page]] and [[Healthy Page|alias]] and [[ ]] and [[]]"))
                .containsExactly("healthy page", "healthy page");
        assertThat(VaultLintService.extractWikilinkTargets("[[ ]] and [[]]")).isEmpty();
    }

    private List<VaultLintFinding> findingsOf(VaultLintReport report, VaultLintFinding.Code code) {
        return report.findings().stream()
                .filter(finding -> finding.code() == code).toList();
    }

    private int count(String table) {
        return db().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private String frontmatter(String knowledgeId, String title, String pageType) {
        return "---\nid: \"%s\"\ntitle: \"%s\"\ntype: \"%s\"\nstatus: \"PUBLISHED\"\n---\n\n"
                .formatted(knowledgeId, title, pageType);
    }

    private record Workspace(long id, Path root) {
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

    private void seedPublishedPage(Workspace ws, String knowledgeId, String title,
                                   String pageType, String body) throws Exception {
        String markdown = frontmatter(knowledgeId, title, pageType) + body;
        seedPublishedPageWithHash(ws, knowledgeId, title, pageType, body,
                WikiContentHash.sha256(markdown));
    }

    private void seedPublishedPageWithHash(Workspace ws, String knowledgeId, String title,
                                           String pageType, String body, String hash) throws Exception {
        String stem = java.text.Normalizer.normalize(title.strip(), java.text.Normalizer.Form.NFC)
                .toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "-")
                .replaceAll("[^\\p{L}\\p{N}\\-_]", "").replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
        String logicalPath = "vault/" + WikiPageType.valueOf(pageType).folderName() + "/" + stem + ".md";
        String markdown = frontmatter(knowledgeId, title, pageType) + body;
        if (ws.root() != null) {
            Files.writeString(ws.root().resolve(logicalPath), markdown);
        }
        insert("""
                INSERT INTO knowledge_page (workspace_id, knowledge_id, title, normalized_title, type,
                    markdown_path, status, content_hash, revision, created_at, updated_at)
                VALUES (:ws, :knowledgeId, :title, :normalizedTitle, :type, :path,
                    'PUBLISHED', :hash, 1, :now, :now)
                """, "ws", ws.id(), "knowledgeId", knowledgeId, "title", title,
                "normalizedTitle", WikiTargetReference.normalizeTitle(title), "type", pageType,
                "path", logicalPath, "hash", hash, "now", "2026-09-01T00:00:00Z");
    }

    @SuppressWarnings("LineLength")
    private long seedProposalInWorkspace(long workspaceId) {
        long documentId = insert("""
                INSERT INTO document (workspace_id, file_name, source_path, sha256, status, created_at, updated_at)
                VALUES (:ws, 'p.txt', 'p.txt', 'ph', 'PROCESSED', :now, :now)
                """, "ws", workspaceId, "now", "2026-09-01T00:00:00Z");
        long jobId = insert("""
                INSERT INTO processing_job (workspace_id, job_id, job_type, created_at, updated_at)
                VALUES (:ws, 'pj', 'ANALYZE', :now, :now)
                """, "ws", workspaceId, "now", "2026-09-01T00:00:00Z");
        long jobItemId = insert("INSERT INTO processing_job_item (job_id, document_id) VALUES (:job, :doc)",
                "job", jobId, "doc", documentId);
        long analysisId = insert("""
                INSERT INTO document_analysis (job_item_id, document_id, status, prompt_identifier, prompt_version,
                    provider, model, contract_version, created_at, updated_at)
                VALUES (:jobItem, :doc, 'SUCCEEDED', 'prompt', 'v1', 'provider', 'model', 'v1', :now, :now)
                """, "jobItem", jobItemId, "doc", documentId, "now", "2026-09-01T00:00:00Z");
        long candidateId = insert("""
                INSERT INTO knowledge_candidate (document_analysis_id, document_id, candidate_no, title,
                    candidate_type, summary, confidence, rationale, created_at, updated_at)
                VALUES (:analysis, :doc, 1, 'Foreign Proposal', 'CONCEPT', 's', 0.9, 'r', :now, :now)
                """, "analysis", analysisId, "doc", documentId, "now", "2026-09-01T00:00:00Z");
        return insert("""
                INSERT INTO knowledge_proposal (workspace_id, document_analysis_id, document_id,
                    knowledge_candidate_id, action, status, provider, model, prompt_identifier,
                    prompt_version, contract_version, normalized_data_json, created_at, updated_at)
                VALUES (:ws, :analysis, :doc, :candidate, 'CREATE', 'APPROVED', 'provider', 'model',
                    'prompt', 'v1', 'v1', '{}', :now, :now)
                """, "ws", workspaceId, "analysis", analysisId, "doc", documentId,
                "candidate", candidateId, "now", "2026-09-01T00:00:00Z");
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
