package org.km.llmwiki.search;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "app.persistence.sqlite.path=target/test-data/fts-search-${random.uuid}/knowledge.db"
})
class FtsSearchIndexRepositoryIntegrationTest extends IsolatedIntegrationTest {

    private static final String HASH = "0123456789abcdef".repeat(4);

    @Autowired
    private FtsSearchIndexRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void createsContractsAndVirtualTablesWithRebuildablePolicies() {
        assertThat(tableExists("search_index_contract")).isTrue();
        assertThat(tableExists("search_index_identity")).isTrue();
        assertThat(tableExists("knowledge_fts")).isTrue();
        assertThat(tableExists("source_fts")).isTrue();

        List<Map<String, Object>> contracts = jdbcClient.sql("""
                        SELECT corpus, stable_identity, indexed_fields, tokenizer,
                               unicode_normalization, source_of_truth, rebuild_strategy, rebuildable
                        FROM search_index_contract ORDER BY corpus
                        """)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "corpus", rs.getString("corpus"),
                        "stableIdentity", rs.getString("stable_identity"),
                        "indexedFields", rs.getString("indexed_fields"),
                        "tokenizer", rs.getString("tokenizer"),
                        "unicode", rs.getString("unicode_normalization"),
                        "sourceOfTruth", rs.getString("source_of_truth"),
                        "rebuildStrategy", rs.getString("rebuild_strategy"),
                        "rebuildable", rs.getInt("rebuildable")))
                .list();

        assertThat(contracts).containsExactly(
                Map.of("corpus", "KNOWLEDGE",
                        "stableIdentity", "workspace_id + knowledge_id",
                        "indexedFields", "title, content",
                        "tokenizer", "unicode61 remove_diacritics 2",
                        "unicode", "NFC before indexing",
                        "sourceOfTruth", "knowledge_page (PUBLISHED) and published vault Markdown",
                        "rebuildStrategy", "clear-and-repopulate from published Wiki",
                        "rebuildable", 1),
                Map.of("corpus", "SOURCE",
                        "stableIdentity", "workspace_id + source_chunk_id",
                        "indexedFields", "normalized_content",
                        "tokenizer", "unicode61 remove_diacritics 2",
                        "unicode", "NFC supplied by source_chunk.normalized_content",
                        "sourceOfTruth", "source_chunk.normalized_content; raw content remains evidence-only",
                        "rebuildStrategy", "clear-and-repopulate from source_chunk",
                        "rebuildable", 1));

        assertThat(columnNames("source_fts")).contains("normalized_content")
                .doesNotContain("content");
        assertThat(columnNames("knowledge_fts")).contains("title", "content", "page_status");
    }

    @Test
    void indexesPublishedKnowledgeAndSourceWithStableIdentityAndWorkspaceIsolation() {
        long firstWorkspace = insertWorkspace("first");
        long secondWorkspace = insertWorkspace("second");

        repository.upsertKnowledge(knowledge(firstWorkspace, "same-topic", "第一個工作區", "SQLite FTS5 中文搜尋"));
        repository.upsertKnowledge(knowledge(secondWorkspace, "same-topic", "第二個工作區", "SQLite FTS5 English"));
        repository.upsertSource(source(firstWorkspace, 101, "中文搜尋 SQLite jOOQ FTS5 2025"));
        repository.upsertSource(source(secondWorkspace, 101, "foreign workspace SQLite"));

        assertThat(repository.matchKnowledge(firstWorkspace, "SQLite FTS5"))
                .extracting(SearchIndexMatch::stableId)
                .containsExactly("same-topic");
        assertThat(repository.matchKnowledge(firstWorkspace, "第二個工作區")).isEmpty();
        assertThat(repository.matchKnowledge(secondWorkspace, "English"))
                .extracting(SearchIndexMatch::stableId)
                .containsExactly("same-topic");

        assertThat(repository.matchSource(firstWorkspace, "中文搜尋"))
                .extracting(SearchIndexMatch::stableId)
                .containsExactly("101");
        assertThat(repository.matchSource(firstWorkspace, "foreign")).isEmpty();
        assertThat(repository.matchSource(secondWorkspace, "foreign"))
                .extracting(SearchIndexMatch::stableId)
                .containsExactly("101");

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM search_index_identity")
                .query(Integer.class).single()).isEqualTo(4);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM search_index_identity WHERE corpus = 'KNOWLEDGE' AND stable_id = 'same-topic'")
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void onlyAllowsPublishedWikiAndIndexesNormalizedSourceWithoutChangingRawEvidence() {
        assertThatThrownBy(() -> new KnowledgeSearchDocument(
                1, "draft", "Draft", "Draft", "draft", "vault/draft.md", "CONCEPT", "DRAFT", HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only PUBLISHED");

        long workspaceId = insertWorkspace("evidence");
        long documentId = insertDocument(workspaceId);
        long chunkId = insertSourceChunk(documentId, "原始內容 Cafe\u0301", "中文搜尋 Café SQLite");
        String rawBefore = jdbcClient.sql("SELECT content FROM source_chunk WHERE id = :id")
                .param("id", chunkId).query(String.class).single();

        repository.upsertSource(source(workspaceId, chunkId, "中文搜尋 Cafe\u0301 SQLite"));

        assertThat(repository.matchSource(workspaceId, "Café"))
                .extracting(SearchIndexMatch::stableId)
                .containsExactly(Long.toString(chunkId));
        assertThat(jdbcClient.sql("SELECT content FROM source_chunk WHERE id = :id")
                .param("id", chunkId).query(String.class).single()).isEqualTo(rawBefore);
        assertThat(jdbcClient.sql("SELECT normalized_content FROM source_fts WHERE source_chunk_id = :id")
                .param("id", Long.toString(chunkId)).query(String.class).single()).isEqualTo("中文搜尋 Café SQLite");
    }

    @Test
    void matchesChineseEnglishNumericAndTechnicalTokensAndBindsUnsafeInputAsLiteral() {
        long workspaceId = insertWorkspace("tokens");
        repository.upsertSource(source(workspaceId, 202, "中文搜尋 SQLite jOOQ FTS5 2025"));

        for (String query : List.of("中文搜尋", "SQLite", "jOOQ", "FTS5", "2025", "SQLite FTS5")) {
            assertThat(repository.matchSource(workspaceId, query))
                    .as("query %s", query)
                    .extracting(SearchIndexMatch::stableId)
                    .containsExactly("202");
        }

        assertThat(repository.matchSource(workspaceId, "\" OR 1=1 --")).isEmpty();
        assertThat(repository.matchSource(workspaceId, "* OR SQLite")).isEmpty();
        assertThatThrownBy(() -> repository.matchSource(workspaceId, "\u0000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");
    }

    @Test
    void clearsAndRebuildsEachCorpusWithoutTouchingTheOtherProjection() {
        long workspaceId = insertWorkspace("rebuild");
        repository.upsertKnowledge(knowledge(workspaceId, "old", "Old", "old SQLite"));
        repository.upsertSource(source(workspaceId, 303, "old source"));

        repository.clearKnowledge(workspaceId);
        assertThat(repository.matchKnowledge(workspaceId, "SQLite")).isEmpty();
        assertThat(repository.matchSource(workspaceId, "old")).hasSize(1);

        repository.rebuildKnowledge(workspaceId, List.of(
                knowledge(workspaceId, "new", "New", "new FTS5")));
        assertThat(repository.matchKnowledge(workspaceId, "FTS5"))
                .extracting(SearchIndexMatch::stableId)
                .containsExactly("new");
        assertThat(repository.matchKnowledge(workspaceId, "old")).isEmpty();

        repository.clearWorkspace(workspaceId);
        assertThat(repository.matchKnowledge(workspaceId, "FTS5")).isEmpty();
        assertThat(repository.matchSource(workspaceId, "old")).isEmpty();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM search_index_identity WHERE workspace_id = :id")
                .param("id", workspaceId).query(Integer.class).single()).isZero();
    }

    private KnowledgeSearchDocument knowledge(long workspaceId, String id, String title, String content) {
        return new KnowledgeSearchDocument(workspaceId, id, title, title, content,
                "vault/concepts/" + id + ".md", "CONCEPT", "PUBLISHED", HASH);
    }

    private SourceSearchDocument source(long workspaceId, long chunkId, String normalizedContent) {
        return new SourceSearchDocument(workspaceId, chunkId, 1, 1, normalizedContent,
                "Notes", "Notes", HASH);
    }

    private long insertWorkspace(String suffix) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path,
                            data_path, status, created_at, updated_at)
                        VALUES (:name, :root, :inbox, :archive, :vault, :data, 'ACTIVE',
                            '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                        """)
                .param("name", "FTS " + suffix)
                .param("root", "/tmp/fts-" + suffix)
                .param("inbox", "/tmp/fts-" + suffix + "/inbox")
                .param("archive", "/tmp/fts-" + suffix + "/archive")
                .param("vault", "/tmp/fts-" + suffix + "/vault")
                .param("data", "/tmp/fts-" + suffix + "/data")
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    private long insertDocument(long workspaceId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO document (workspace_id, file_name, source_path, sha256,
                            created_at, updated_at)
                        VALUES (:workspaceId, 'evidence.md', 'inbox/evidence.md', :hash,
                            '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """)
                .param("workspaceId", workspaceId)
                .param("hash", HASH)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    private long insertSourceChunk(long documentId, String raw, String normalized) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO source_chunk (document_id, chunk_no, content, normalized_content,
                            content_hash, created_at, updated_at)
                        VALUES (:documentId, 1, :raw, :normalized, :hash,
                            '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                        """)
                .param("documentId", documentId)
                .param("raw", raw)
                .param("normalized", normalized)
                .param("hash", HASH)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    private boolean tableExists(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM sqlite_master WHERE name = :name")
                .param("name", table).query(Integer.class).single() == 1;
    }

    private List<String> columnNames(String table) {
        return jdbcClient.sql("PRAGMA table_info(" + table + ")")
                .query((rs, rowNum) -> rs.getString("name")).list();
    }
}
