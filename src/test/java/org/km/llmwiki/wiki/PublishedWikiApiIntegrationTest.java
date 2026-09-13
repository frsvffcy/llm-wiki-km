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
 * Read-only Published Wiki consumption surface (#373): list/read are workspace-scoped,
 * PUBLISHED-only, and content is hash-validated canonical markdown. Non-published rows,
 * foreign workspaces, and unknown ids are indistinguishable 404s; the response never
 * carries filesystem paths.
 */
class PublishedWikiApiIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @TempDir
    Path tempDir;

    @Test
    void listsOnlyPublishedPagesOfTheActiveWorkspaceWithPaginationAndTypeFilter() throws Exception {
        Workspace active = createWorkspace("active");
        seedPublishedPage(active, "wiki-arch", "Transformer Architecture", "CONCEPT",
                "# Transformer Architecture\n\nAttention is all you need.");
        seedPublishedPage(active, "wiki-howto", "Rescan How To", "HOWTO",
                "# Rescan How To\n\nDrop files into inbox.");
        seedPublishedPage(active, "wiki-draft-page", "Unpublished Draft", "CONCEPT",
                "# Unpublished Draft\n\nNot visible.", "DRAFT");
        createWorkspace("inactive");
        seedPublishedPage(new Workspace(lookupWorkspaceId("inactive"), null), "wiki-foreign",
                "Foreign Page", "CONCEPT", "# Foreign Page\n\nOther workspace.");

        mockMvc.perform(get("/api/v1/wiki"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].knowledgeId").value("wiki-arch"))
                .andExpect(jsonPath("$.data[0].title").value("Transformer Architecture"))
                .andExpect(jsonPath("$.data[0].pageType").value("CONCEPT"))
                .andExpect(jsonPath("$.data[0].revision").value(1))
                .andExpect(jsonPath("$.data[0].contentHash").isString())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(result -> assertNoFilesystemPaths(result.getResponse().getContentAsString()));

        mockMvc.perform(get("/api/v1/wiki").param("pageType", "HOWTO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].knowledgeId").value("wiki-howto"));

        mockMvc.perform(get("/api/v1/wiki").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].knowledgeId").value("wiki-howto"))
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    void readsOnePublishedPageWithHashValidatedCanonicalBody() throws Exception {
        Workspace active = createWorkspace("active");
        seedPublishedPage(active, "wiki-arch", "Transformer Architecture", "CONCEPT",
                "# Transformer Architecture\n\nAttention is all you need.");

        mockMvc.perform(get("/api/v1/wiki/{knowledgeId}", "wiki-arch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.knowledgeId").value("wiki-arch"))
                .andExpect(jsonPath("$.data.title").value("Transformer Architecture"))
                .andExpect(jsonPath("$.data.pageType").value("CONCEPT"))
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.markdown").value(
                        "# Transformer Architecture\n\nAttention is all you need."))
                .andExpect(result -> assertNoFilesystemPaths(result.getResponse().getContentAsString()));
    }

    @Test
    void unknownOrNonPublishedOrForeignPagesAreIndistinguishable404s() throws Exception {
        Workspace active = createWorkspace("active");
        seedPublishedPage(active, "wiki-arch", "Transformer Architecture", "CONCEPT",
                "# Transformer Architecture\n\nBody.");
        seedPublishedPage(active, "wiki-draft-page", "Unpublished Draft", "CONCEPT",
                "# Unpublished Draft\n\nNot visible.", "DRAFT");

        mockMvc.perform(get("/api/v1/wiki/{knowledgeId}", "wiki-unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WIKI_PAGE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/wiki/{knowledgeId}", "wiki-draft-page"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WIKI_PAGE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/wiki/{knowledgeId}", "wiki-foreign"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WIKI_PAGE_NOT_FOUND"));
    }

    @Test
    void emptyWorkspaceYieldsAnEmptyListAndFailuresStayTyped() throws Exception {
        createWorkspace("active");

        mockMvc.perform(get("/api/v1/wiki"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(0));

        mockMvc.perform(get("/api/v1/wiki").param("size", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/wiki").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/wiki").param("pageType", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    private void assertNoFilesystemPaths(String body) {
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("/tmp/", "/Users/", "tempDir", "vault_path",
                        "markdown_path");
    }

    private record Workspace(long id, Path root) {
    }

    private Workspace createWorkspace(String name) throws Exception {
        Path root = tempDir.resolve(name);
        Files.createDirectories(root.resolve("vault/concepts"));
        Files.createDirectories(root.resolve("vault/howtos"));
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

    private long lookupWorkspaceId(String name) {
        return db().sql("SELECT id FROM workspace WHERE name = :name").param("name", name)
                .query(Long.class).single();
    }

    private void seedPublishedPage(Workspace workspace, String knowledgeId, String title,
                                   String pageType, String body) throws Exception {
        seedPublishedPage(workspace, knowledgeId, title, pageType, body, "PUBLISHED");
    }

    private void seedPublishedPage(Workspace workspace, String knowledgeId, String title,
                                   String pageType, String body, String status) throws Exception {
        // Mirror WikiPathContract.normalizeTitleToFileName exactly: the canonical path
        // authority requires the seeded markdown_path to be path-contract-canonical.
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
                status: "%s"
                ---

                %s""".formatted(knowledgeId, title, pageType, status, body);
        String hash = WikiContentHash.sha256(markdown);
        if (workspace.root() != null) {
            Files.writeString(workspace.root().resolve(logicalPath), markdown);
        }
        long id = insert("""
                INSERT INTO knowledge_page (workspace_id, knowledge_id, title, normalized_title, type,
                    markdown_path, status, content_hash, revision, created_at, updated_at)
                VALUES (:workspace, :knowledgeId, :title, :normalizedTitle, :type, :path,
                    :status, :hash, 1, :now, :now)
                """, "workspace", workspace.id(), "knowledgeId", knowledgeId, "title", title,
                "normalizedTitle", WikiTargetReference.normalizeTitle(title), "type", pageType,
                "path", logicalPath, "status", status, "hash", hash, "now", "2026-09-01T00:00:00Z");
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
        if (key == null) {
            throw new IllegalStateException("insert did not return a key");
        }
        return key.longValue();
    }
}
