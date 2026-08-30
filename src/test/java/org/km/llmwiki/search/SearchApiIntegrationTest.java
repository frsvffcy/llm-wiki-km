package org.km.llmwiki.search;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties =
        "app.persistence.sqlite.path=target/test-data/search-api-${random.uuid}/knowledge.db")
@AutoConfigureMockMvc
class SearchApiIntegrationTest extends IsolatedIntegrationTest {

    private static final String HASH = "abcdef0123456789".repeat(4);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FtsSearchIndexRepository repository;

    @Autowired
    private SearchService searchService;

    @Test
    void searchesWikiWithStableProvenanceAndTitleBoost() throws Exception {
        long workspaceId = insertWorkspace("wiki", "ACTIVE");
        insertWiki(workspaceId, "content-hit", "一般筆記", "quantum 出現在內文", "REFERENCE", 2);
        insertWiki(workspaceId, "title-hit", "Quantum 指南", "一般內容", "HOWTO", 4);

        mockMvc.perform(get("/api/v1/search")
                        .param("query", "quantum")
                        .param("corpus", "WIKI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.data[0].kind").value("WIKI"))
                .andExpect(jsonPath("$.data[0].stableId").value("title-hit"))
                .andExpect(jsonPath("$.data[0].knowledgeId").value("title-hit"))
                .andExpect(jsonPath("$.data[0].title").value("Quantum 指南"))
                .andExpect(jsonPath("$.data[0].pageType").value("HOWTO"))
                .andExpect(jsonPath("$.data[0].path").value("howtos/title-hit.md"))
                .andExpect(jsonPath("$.data[0].revision").value(4))
                .andExpect(jsonPath("$.data[0].workspace.id").value(workspaceId))
                .andExpect(jsonPath("$.data[0].workspace.name").value("Search wiki"))
                .andExpect(jsonPath("$.data[0].snippet", containsString("<mark>Quantum</mark>")));
    }

    @Test
    void searchesSourceWithEvidenceProvenanceAndDocumentFilter() throws Exception {
        long workspaceId = insertWorkspace("source", "ACTIVE");
        SourceFixture selected = insertSource(workspaceId, "selected.pdf", 3, 8,
                "摘要", "第一章 > 摘要", "SQLite evidence search");
        insertSource(workspaceId, "other.pdf", 1, null,
                null, null, "SQLite other evidence");

        mockMvc.perform(get("/api/v1/search")
                        .param("query", "SQLite")
                        .param("corpus", "SOURCE")
                        .param("documentId", Long.toString(selected.documentId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].kind").value("SOURCE_CHUNK"))
                .andExpect(jsonPath("$.data[0].stableId").value(Long.toString(selected.chunkId())))
                .andExpect(jsonPath("$.data[0].sourceChunkId").value(selected.chunkId()))
                .andExpect(jsonPath("$.data[0].documentId").value(selected.documentId()))
                .andExpect(jsonPath("$.data[0].documentName").value("selected.pdf"))
                .andExpect(jsonPath("$.data[0].chunkNo").value(3))
                .andExpect(jsonPath("$.data[0].pageNo").value(8))
                .andExpect(jsonPath("$.data[0].section").value("摘要"))
                .andExpect(jsonPath("$.data[0].headingPath").value("第一章 > 摘要"))
                .andExpect(jsonPath("$.data[0].snippet", containsString("<mark>SQLite</mark>")));
    }

    @Test
    void fusesAllCorporaByPerCorpusRankAndStableKindTieBreaker() throws Exception {
        long workspaceId = insertWorkspace("all", "ACTIVE");
        insertWiki(workspaceId, "wiki-a", "Alpha Wiki", "fusion common", "CONCEPT", 1);
        insertWiki(workspaceId, "wiki-b", "Beta Wiki", "fusion common", "CONCEPT", 1);
        SourceFixture sourceA = insertSource(workspaceId, "a.txt", 1, null,
                null, null, "fusion common");
        SourceFixture sourceB = insertSource(workspaceId, "b.txt", 1, null,
                null, null, "fusion common");

        mockMvc.perform(get("/api/v1/search").param("query", "fusion").param("corpus", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.data[0].stableId").value(Long.toString(sourceA.chunkId())))
                .andExpect(jsonPath("$.data[1].stableId").value("wiki-a"))
                .andExpect(jsonPath("$.data[2].stableId").value(Long.toString(sourceB.chunkId())))
                .andExpect(jsonPath("$.data[3].stableId").value("wiki-b"))
                .andExpect(jsonPath("$.data[0].score").value(1.0d / 61))
                .andExpect(jsonPath("$.data[1].score").value(1.0d / 61));
    }

    @Test
    void paginatesWithStableIdTieBreakerAndRejectsOversizedPage() throws Exception {
        long workspaceId = insertWorkspace("pages", "ACTIVE");
        insertWiki(workspaceId, "b-id", "Same", "pagination token", "CONCEPT", 1);
        insertWiki(workspaceId, "a-id", "Same", "pagination token", "CONCEPT", 1);

        mockMvc.perform(get("/api/v1/search")
                        .param("query", "pagination").param("corpus", "WIKI")
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].stableId").value("a-id"))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.totalPages").value(2));

        mockMvc.perform(get("/api/v1/search")
                        .param("query", "pagination").param("corpus", "WIKI")
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].stableId").value("b-id"));

        mockMvc.perform(get("/api/v1/search")
                        .param("query", "pagination").param("size", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message", containsString("between 1 and 200")));
    }

    @Test
    void appliesControlledPageTypeAndCorpusSpecificAllFilters() throws Exception {
        long workspaceId = insertWorkspace("filters", "ACTIVE");
        insertWiki(workspaceId, "concept", "Filter", "filterable", "CONCEPT", 1);
        insertWiki(workspaceId, "howto", "Filter", "filterable", "HOWTO", 1);
        SourceFixture wanted = insertSource(workspaceId, "wanted.txt", 1, null,
                null, null, "filterable");
        insertSource(workspaceId, "other.txt", 1, null,
                null, null, "filterable");

        mockMvc.perform(get("/api/v1/search")
                        .param("query", "filterable").param("corpus", "ALL")
                        .param("pageType", "HOWTO")
                        .param("documentId", Long.toString(wanted.documentId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.data[0].documentId").value(wanted.documentId()))
                .andExpect(jsonPath("$.data[1].knowledgeId").value("howto"));

        mockMvc.perform(get("/api/v1/search")
                        .param("query", "filterable").param("corpus", "SOURCE")
                        .param("pageType", "CONCEPT"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void isolatesEveryCorpusToTheSingleActiveWorkspace() throws Exception {
        long active = insertWorkspace("active", "ACTIVE");
        long hidden = insertWorkspace("hidden", "INACTIVE");
        insertWiki(active, "visible", "Visible", "isolation marker", "CONCEPT", 1);
        insertSource(active, "visible.txt", 1, null, null, null, "isolation marker");
        insertWiki(hidden, "hidden", "Hidden", "isolation marker", "CONCEPT", 1);
        insertSource(hidden, "hidden.txt", 1, null, null, null, "isolation marker");

        mockMvc.perform(get("/api/v1/search").param("query", "isolation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.data[0].workspace.id").value(active))
                .andExpect(jsonPath("$.data[1].workspace.id").value(active));
    }

    @Test
    void boundsChineseSnippetWithoutBreakingUnicodeOrHighlightMarkers() {
        long workspaceId = insertWorkspace("unicode", "ACTIVE");
        String content = "前言".repeat(400) + " 中文搜尋 " + "後文".repeat(400);
        insertWiki(workspaceId, "zh", "中文頁面", content, "REFERENCE", 1);

        SearchResult result = searchService.search("中文搜尋", "WIKI", null,
                        null, 0, 20).data().getFirst();

        assertThat(result.snippet()).contains("<mark>中文搜尋</mark>");
        String visible = result.snippet().replace("<mark>", "").replace("</mark>", "");
        assertThat(visible.codePointCount(0, visible.length())).isLessThanOrEqualTo(281);
        assertThat(result.snippet()).doesNotContain("\uFFFD");
    }

    @Test
    void treatsDangerousTextAsLiteralAndRejectsMalformedOrControlOnlyQueries() throws Exception {
        long workspaceId = insertWorkspace("safe", "ACTIVE");
        insertWiki(workspaceId, "safe", "SQLite", "safe searchable content", "CONCEPT", 1);

        mockMvc.perform(get("/api/v1/search").param("query", "\" OR 1=1 --"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0));

        mockMvc.perform(get("/api/v1/search").param("query", "*** ((("))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThatThrownBy(() -> searchService.search("\u0000", "ALL", null,
                null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");
    }

    @Test
    void returnsStableEmptyPageForBlankAndNoResultQueries() throws Exception {
        insertWorkspace("empty", "ACTIVE");

        for (String query : List.of("", "   ", "missing")) {
            mockMvc.perform(get("/api/v1/search").param("query", query))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty())
                    .andExpect(jsonPath("$.page.number").value(0))
                    .andExpect(jsonPath("$.page.size").value(20))
                    .andExpect(jsonPath("$.page.totalElements").value(0))
                    .andExpect(jsonPath("$.page.totalPages").value(0));
        }
    }

    private long insertWorkspace(String suffix, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path,
                            data_path, config_path, status, created_at, updated_at)
                        VALUES (:name, :root, :inbox, :archive, :vault, :data, :config, :status,
                            '2026-08-30T00:00:00Z', '2026-08-30T00:00:00Z')
                        """)
                .param("name", "Search " + suffix)
                .param("root", "/tmp/search-" + suffix)
                .param("inbox", "/tmp/search-" + suffix + "/inbox")
                .param("archive", "/tmp/search-" + suffix + "/archive")
                .param("vault", "/tmp/search-" + suffix + "/vault")
                .param("data", "/tmp/search-" + suffix + "/data")
                .param("config", "/tmp/search-" + suffix + "/config")
                .param("status", status)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    private void insertWiki(long workspaceId, String knowledgeId, String title, String content,
                            String pageType, int revision) {
        db().sql("""
                        INSERT INTO knowledge_page (workspace_id, knowledge_id, title, normalized_title,
                            type, markdown_path, status, content_hash, revision, created_at, updated_at,
                            published_at)
                        VALUES (:workspaceId, :knowledgeId, :title, :normalizedTitle, :type, :path,
                            'PUBLISHED', :hash, :revision, '2026-08-30T00:00:00Z',
                            '2026-08-30T00:00:00Z', '2026-08-30T00:00:00Z')
                        """)
                .param("workspaceId", workspaceId)
                .param("knowledgeId", knowledgeId)
                .param("title", title)
                .param("normalizedTitle", title.toLowerCase())
                .param("type", pageType)
                .param("path", pageType.toLowerCase() + "s/" + knowledgeId + ".md")
                .param("hash", HASH)
                .param("revision", revision)
                .update();
        repository.upsertKnowledge(new KnowledgeSearchDocument(workspaceId, knowledgeId, title,
                title.toLowerCase(), content, pageType.toLowerCase() + "s/" + knowledgeId + ".md",
                pageType, "PUBLISHED", HASH));
    }

    private SourceFixture insertSource(long workspaceId, String fileName, int chunkNo,
                                       Integer pageNo, String section, String headingPath,
                                       String content) {
        KeyHolder documentKey = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO document (workspace_id, file_name, original_file_name, extension,
                            source_path, sha256, status, created_at, updated_at)
                        VALUES (:workspaceId, :fileName, :fileName, 'txt', :sourcePath, :hash,
                            'PROCESSED', '2026-08-30T00:00:00Z', '2026-08-30T00:00:00Z')
                        """)
                .param("workspaceId", workspaceId)
                .param("fileName", fileName)
                .param("sourcePath", "archive/" + fileName)
                .param("hash", HASH)
                .update(documentKey);
        long documentId = documentKey.getKey().longValue();

        KeyHolder chunkKey = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO source_chunk (document_id, chunk_no, page_no, section, heading_path,
                            content, normalized_content, content_hash, created_at, updated_at)
                        VALUES (:documentId, :chunkNo, :pageNo, :section, :headingPath, :content,
                            :content, :hash, '2026-08-30T00:00:00Z', '2026-08-30T00:00:00Z')
                        """)
                .param("documentId", documentId)
                .param("chunkNo", chunkNo)
                .param("pageNo", pageNo)
                .param("section", section)
                .param("headingPath", headingPath)
                .param("content", content)
                .param("hash", HASH)
                .update(chunkKey);
        long chunkId = chunkKey.getKey().longValue();
        repository.upsertSource(new SourceSearchDocument(workspaceId, chunkId, documentId,
                chunkNo, pageNo, content, section, headingPath, HASH));
        return new SourceFixture(documentId, chunkId);
    }

    private record SourceFixture(long documentId, long chunkId) {
    }
}
