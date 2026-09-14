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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Read-only Vault Lint triage surface (#383): the endpoint is a pure backend-authority
 * projection of {@code VaultLintService} — workspace-scoped, deterministic, carrying no
 * filesystem paths. The Browser renders from it without re-running lint.
 */
class VaultLintApiIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @TempDir
    Path tempDir;

    @Test
    void listsActiveWorkspaceFindingsWithDeterministicOrderAndNoPaths() throws Exception {
        Workspace active = createWorkspace("active", "2026-09-01T00:00:02Z");
        seedPublishedPage(active, "wiki-hub", "Hub Page", "CONCEPT",
                "# Hub Page\n\nSee [[Healthy Page]] and [[Missing Page]].");
        seedPublishedPage(active, "wiki-healthy", "Healthy Page", "CONCEPT",
                "# Healthy Page\n\nBack to [[Hub Page]].");
        seedPublishedPage(active, "wiki-lonely", "Lonely Page", "CONCEPT",
                "# Lonely Page\n\nNobody links here.");

        mockMvc.perform(get("/api/v1/vault-lint/findings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspaceId").value(active.id()))
                .andExpect(jsonPath("$.data.checkedPageCount").value(3))
                .andExpect(jsonPath("$.data.findings.length()").value(2))
                .andExpect(jsonPath("$.data.findings[0].finding.code").value("BROKEN_INTERNAL_LINK"))
                .andExpect(jsonPath("$.data.findings[0].finding.category").value("REFERENCE"))
                .andExpect(jsonPath("$.data.findings[0].finding.severity").value("ERROR"))
                .andExpect(jsonPath("$.data.findings[0].finding.knowledgeId").value("wiki-hub"))
                .andExpect(jsonPath("$.data.findings[0].finding.logicalPath").value(
                        "vault/concepts/hub-page.md"))
                .andExpect(jsonPath("$.data.findings[0].finding.detail").value(
                        "wikilink target not published: missing page"))
                .andExpect(jsonPath("$.data.findings[1].finding.code").value("ORPHAN_PAGE"))
                .andExpect(jsonPath("$.data.findings[1].finding.severity").value("WARNING"))
                .andExpect(jsonPath("$.data.findings[1].finding.knowledgeId").value("wiki-lonely"))
                .andExpect(jsonPath("$.data.findings[0].repairEligible").value(false))
                .andExpect(jsonPath("$.data.findings[0].repairRefusalReason").value("AMBIGUOUS_TARGET"))
                .andExpect(jsonPath("$.data.findings[1].repairEligible").value(false))
                .andExpect(jsonPath("$.data.findings[1].repairRefusalReason").value("SEMANTIC_JUDGMENT_REQUIRED"))
                .andExpect(result -> assertNoFilesystemPaths(result.getResponse().getContentAsString()));

        // A second read is byte-identical: the projection is deterministic.
        String first = mockMvc.perform(get("/api/v1/vault-lint/findings"))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(get("/api/v1/vault-lint/findings"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getContentAsString()).isEqualTo(first));
    }

    @Test
    void foreignWorkspaceFindingsStayInvisible() throws Exception {
        Workspace other = createWorkspace("other", "2026-09-01T00:00:01Z");
        seedPublishedPage(other, "wiki-foreign", "Foreign Page", "CONCEPT",
                "# Foreign Page\n\nSee [[Nowhere Else]].");
        Workspace active = createWorkspace("active", "2026-09-01T00:00:02Z");
        seedPublishedPage(active, "wiki-local", "Local Page", "CONCEPT",
                "# Local Page\n\nSee [[Foreign Page]].");

        mockMvc.perform(get("/api/v1/vault-lint/findings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspaceId").value(active.id()))
                .andExpect(jsonPath("$.data.checkedPageCount").value(1))
                .andExpect(jsonPath("$.data.findings.length()").value(2))
                .andExpect(jsonPath("$.data.findings[0].finding.code").value("BROKEN_INTERNAL_LINK"))
                .andExpect(jsonPath("$.data.findings[0].finding.knowledgeId").value("wiki-local"))
                .andExpect(jsonPath("$.data.findings[0].finding.detail").value(
                        "wikilink target not published: foreign page"))
                .andExpect(jsonPath("$.data.findings[1].finding.code").value("ORPHAN_PAGE"));
    }

    @Test
    void noActiveWorkspaceIsATyped404() throws Exception {
        mockMvc.perform(get("/api/v1/vault-lint/findings"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_WORKSPACE"));
    }

    @Test
    void emptyWorkspaceYieldsZeroFindings() throws Exception {
        createWorkspace("active", "2026-09-01T00:00:02Z");

        mockMvc.perform(get("/api/v1/vault-lint/findings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkedPageCount").value(0))
                .andExpect(jsonPath("$.data.findings.length()").value(0));
    }

    private void assertNoFilesystemPaths(String body) {
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("/tmp/", "/Users/", "tempDir", "vault_path",
                        "markdown_path", "root_path");
    }

    private record Workspace(long id, Path root) {
    }

    private Workspace createWorkspace(String name, String createdAt) throws Exception {
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
                "data", root.resolve("data").toString(), "now", createdAt);
        return new Workspace(id, root);
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
        long id = insert("""
                INSERT INTO knowledge_page (workspace_id, knowledge_id, title, normalized_title, type,
                    markdown_path, status, content_hash, revision, created_at, updated_at)
                VALUES (:workspace, :knowledgeId, :title, :normalizedTitle, :type, :path,
                    'PUBLISHED', :hash, 1, :now, :now)
                """, "workspace", workspace.id(), "knowledgeId", knowledgeId, "title", title,
                "normalizedTitle", WikiTargetReference.normalizeTitle(title), "type", pageType,
                "path", logicalPath, "status", "PUBLISHED", "hash", hash, "now", "2026-09-01T00:00:00Z");
        org.assertj.core.api.Assertions.assertThat(id).isPositive();
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
}
