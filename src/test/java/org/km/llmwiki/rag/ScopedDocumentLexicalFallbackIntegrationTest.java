package org.km.llmwiki.rag;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.search.SearchCandidatePage;
import org.km.llmwiki.search.SearchCorpus;
import org.km.llmwiki.search.SearchQuery;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Production-equivalent upload → extraction → chunk → FTS regression for #605. */
class ScopedDocumentLexicalFallbackIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SearchService searchService;

    @Autowired
    private RetrievalService retrievalService;

    @Test
    void scopedFallbackRecoversNaturalQueriesWithoutChangingAuthorityOrGlobalSemantics()
            throws Exception {
        createWorkspace();
        long selected = uploadAndExtract("filenameonlymarker.md", """
                # SQLite 操作筆記

                busy_timeout 控制資料庫鎖定時的等待毫秒數。
                知識管理需要明確的來源與可追蹤引用。
                exactbodytoken 只存在於這份文件的正文。
                """);
        long foreign = uploadAndExtract("foreign.md",
                ("busy_timeout foreign ranking noise ").repeat(200));

        assertSelectedCandidate("exactbodytoken", selected);
        assertSelectedEvidence("exactbodytoken", selected);
        assertSelectedCandidate("知識管理", selected);
        assertSelectedEvidence("知識管理", selected);

        String naturalQuestion = "busy_timeout 這個設定要怎麼調整";
        assertThat(unscopedSource(naturalQuestion).items()).isEmpty();
        assertSelectedCandidate(naturalQuestion, selected);
        EvidenceBundle natural = retrieveScoped(naturalQuestion, selected);
        assertThat(natural.query()).isEqualTo(naturalQuestion);
        assertThat(natural.items()).isNotEmpty()
                .allSatisfy(item -> assertThat(item.documentId()).isEqualTo(selected));

        String missingTerm = "busy_timeout unicorn";
        assertThat(unscopedSource(missingTerm).items()).isEmpty();
        assertSelectedCandidate(missingTerm, selected);
        assertSelectedEvidence(missingTerm, selected);

        assertSelectedCandidate("請問知識管理如何運作？", selected);
        assertSelectedEvidence("請問知識管理如何運作？", selected);

        // v2 paraphrase corpus: unseen orderings and filler terms absent from the source.
        // v1 hard-coded prefix/suffix allowlist could not recover these shapes.
        String[] paraphrases = {
                "知識管理到底該如何理解",
                "關於知識管理，到底該如何理解呢？",
                "可以分享一些關於知識管理的看法嗎",
                "這份文件的知識管理是在講什麼東西",
                "知識管理 unicorn 外星詞到底是什麼樣的概念",
                "那個控制鎖定等待的 busy_timeout 要去哪裡改",
                "busy_timeout 這個參數背後的等待機制是什麼",
                "exactbodytoken 相關的說明在哪裡可以找到"};
        for (String paraphrase : paraphrases) {
            assertThat(unscopedSource(paraphrase).items())
                    .as("paraphrase must stay empty without document scope: %s", paraphrase)
                    .isEmpty();
            assertSelectedCandidate(paraphrase, selected);
            assertSelectedEvidence(paraphrase, selected);
        }

        // Corpus-mismatch negatives must never be forced into evidence.
        assertThat(scopedSource("完全無關的自然語句", selected).items()).isEmpty();
        assertThat(retrieveScoped("完全無關的自然語句", selected).items()).isEmpty();
        assertThat(scopedSource("外星科技與量子傳送的原理是什麼", selected).items()).isEmpty();
        assertThat(retrieveScoped("外星科技與量子傳送的原理是什麼", selected).items()).isEmpty();
        assertThat(scopedSource("alpha unicorn", selected).items()).isEmpty();
        assertThat(retrieveScoped("alpha unicorn", selected).items()).isEmpty();

        assertThat(scopedSource("filenameonlymarker", selected).items()).isEmpty();
        assertThat(retrieveScoped("filenameonlymarker", selected).items()).isEmpty();

        assertThat(scopedSource(naturalQuestion, foreign).items()).isNotEmpty()
                .allSatisfy(candidate -> assertThat(candidate.documentId()).isEqualTo(foreign));
        assertThat(scopedSource(naturalQuestion, selected).items())
                .allSatisfy(candidate -> assertThat(candidate.documentId()).isEqualTo(selected));

        db().sql("""
                        UPDATE source_search_index_sync
                        SET indexed_fingerprint = :fingerprint
                        WHERE document_id = :id
                        """)
                .param("fingerprint", "0".repeat(64)).param("id", selected).update();
        assertThat(scopedSource(naturalQuestion, selected).items()).isEmpty();
        assertThat(retrieveScoped(naturalQuestion, selected).items()).isEmpty();
        db().sql("""
                        UPDATE source_search_index_sync
                        SET indexed_fingerprint = canonical_fingerprint
                        WHERE document_id = :id
                        """)
                .param("id", selected).update();

        db().sql("UPDATE document SET status = 'SUPERSEDED' WHERE id = :id")
                .param("id", selected).update();
        assertThat(scopedSource(naturalQuestion, selected).items()).isEmpty();
        assertThat(retrieveScoped(naturalQuestion, selected).items()).isEmpty();

        db().sql("UPDATE document SET status = 'DELETED' WHERE id = :id")
                .param("id", selected).update();
        assertThat(scopedSource(naturalQuestion, selected).items()).isEmpty();
        assertThat(retrieveScoped(naturalQuestion, selected).items()).isEmpty();
    }

    private void assertSelectedCandidate(String query, long documentId) {
        assertThat(scopedSource(query, documentId).items()).isNotEmpty()
                .allSatisfy(candidate -> assertThat(candidate.documentId()).isEqualTo(documentId));
    }

    private void assertSelectedEvidence(String query, long documentId) {
        EvidenceBundle bundle = retrieveScoped(query, documentId);
        assertThat(bundle.query()).isEqualTo(query);
        assertThat(bundle.items()).isNotEmpty()
                .allSatisfy(item -> assertThat(item.documentId()).isEqualTo(documentId));
    }

    private SearchCandidatePage scopedSource(String query, long documentId) {
        return searchService.findCandidates(
                new SearchQuery(query, SearchCorpus.SOURCE, null, documentId, 0, 20));
    }

    private SearchCandidatePage unscopedSource(String query) {
        return searchService.findCandidates(
                new SearchQuery(query, SearchCorpus.SOURCE, null, null, 0, 20));
    }

    private EvidenceBundle retrieveScoped(String query, long documentId) {
        return retrievalService.retrieve(RetrievalRequest.of(
                query, RetrievalMode.HYBRID_FTS, RetrievalStrategy.LEXICAL,
                8, 20_000, new DocumentRetrievalScope(documentId)));
    }

    private long uploadAndExtract(String fileName, String content) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/inbox/files")
                        .file(new MockMultipartFile("file", fileName, "text/markdown",
                                content.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long documentId = Long.parseLong(response.replaceAll(".*\"documentId\":(\\d+).*", "$1"));
        mockMvc.perform(post("/api/v1/documents/{documentId}/extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus").value("PROCESSED"));
        return documentId;
    }

    private void createWorkspace() throws Exception {
        Path root = Path.of("target/test-data/scoped-query-root-" + UUID.randomUUID())
                .toAbsolutePath();
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Scoped Query Test", "rootPath": "%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated());
    }
}
