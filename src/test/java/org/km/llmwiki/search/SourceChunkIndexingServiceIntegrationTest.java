package org.km.llmwiki.search;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "app.persistence.sqlite.path=target/test-data/source-index-${random.uuid}/knowledge.db"
})
class SourceChunkIndexingServiceIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private SourceChunkIndexingService indexingService;

    @Autowired
    private FtsSearchIndexRepository ftsRepository;

    @Test
    void indexesOnlyNormalizedContentWithStableEvidenceProvenanceAndWorkspaceIsolation() throws Exception {
        long firstWorkspace = insertWorkspace("first");
        long secondWorkspace = insertWorkspace("second");
        long firstDocument = insertDocument(firstWorkspace, "PENDING", "PROCESSED");
        long secondDocument = insertDocument(secondWorkspace, "PENDING", "PROCESSED");
        String normalized = "# 證據\n\n中文搜尋 Café SQLite jOOQ FTS5 2026";
        String raw = "# 證據\r\n\r\n中文搜尋 Cafe\u0301 SQLite jOOQ FTS5 2026 raw-only-audit";
        long firstChunk = insertChunk(firstDocument, 1, 3, "證據", "文件 > 證據",
                raw, normalized, sha256(normalized));
        long secondChunk = insertChunk(secondDocument, 1, 9, "Foreign", "Foreign",
                "foreign workspace", "foreign workspace token", sha256("foreign workspace token"));

        SourceIndexSyncResult first = indexingService.reindexDocument(firstWorkspace, firstDocument);
        SourceIndexSyncResult second = indexingService.reindexDocument(secondWorkspace, secondDocument);

        assertThat(first.status()).isEqualTo(SourceIndexSyncStatus.SYNCED);
        assertThat(second.status()).isEqualTo(SourceIndexSyncStatus.SYNCED);
        for (String token : List.of("中文搜尋", "Café", "SQLite", "jOOQ", "FTS5", "2026")) {
            assertThat(ftsRepository.matchSource(firstWorkspace, token))
                    .as("MATCH token %s", token)
                    .extracting(SearchIndexMatch::stableId)
                    .containsExactly(Long.toString(firstChunk));
        }
        assertThat(ftsRepository.matchSource(firstWorkspace, "raw-only-audit")).isEmpty();
        assertThat(ftsRepository.matchSource(firstWorkspace, "foreign")).isEmpty();
        assertThat(ftsRepository.matchSource(secondWorkspace, "foreign"))
                .extracting(SearchIndexMatch::stableId)
                .containsExactly(Long.toString(secondChunk));

        assertThat(ftsRepository.matchSourceEvidence(firstWorkspace, "SQLite"))
                .singleElement().satisfies(match -> {
                    assertThat(match.sourceChunkId()).isEqualTo(firstChunk);
                    assertThat(match.workspaceId()).isEqualTo(firstWorkspace);
                    assertThat(match.documentId()).isEqualTo(firstDocument);
                    assertThat(match.chunkNo()).isEqualTo(1);
                    assertThat(match.pageNo()).isEqualTo(3);
                    assertThat(match.section()).isEqualTo("證據");
                    assertThat(match.headingPath()).isEqualTo("文件 > 證據");
                    assertThat(match.normalizedContent()).isEqualTo(normalized);
                    assertThat(match.contentHash()).isEqualTo(sha256(normalized));
                });
        assertThat(db().sql("SELECT content FROM source_chunk WHERE id = :id")
                .param("id", firstChunk).query(String.class).single()).isEqualTo(raw);

        long stableRowId = identityRowId(firstWorkspace, firstChunk);
        indexingService.reindexDocument(firstWorkspace, firstDocument);
        assertThat(identityRowId(firstWorkspace, firstChunk)).isEqualTo(stableRowId);
        assertThat(countSourceIdentity(firstWorkspace)).isEqualTo(1);
        assertThat(countSourceFts(firstWorkspace)).isEqualTo(1);

        SourceIndexSyncResult wrongWorkspace = indexingService.reindexSourceChunk(firstWorkspace, secondChunk);
        assertThat(wrongWorkspace.status()).isEqualTo(SourceIndexSyncStatus.NOT_FOUND);
        assertThat(ftsRepository.matchSource(secondWorkspace, "foreign")).hasSize(1);
    }

    @Test
    void excludesUnsupportedFailedAndInvalidCanonicalChunksWithoutMisleadingRows() throws Exception {
        long workspaceId = insertWorkspace("eligibility");
        long unsupported = insertDocument(workspaceId, "PENDING", "UNSUPPORTED");
        insertChunk(unsupported, 1, 1, null, null, "raw", "unsupported searchable",
                sha256("unsupported searchable"));
        long failed = insertDocument(workspaceId, "PENDING", "FAILED");
        insertChunk(failed, 1, 1, null, null, "raw", "failed searchable",
                sha256("failed searchable"));
        long processed = insertDocument(workspaceId, "PENDING", "PROCESSED");
        long validChunk = insertChunk(processed, 1, 1, "Valid", "Valid", "raw valid",
                "valid searchable", sha256("valid searchable"));
        insertChunk(processed, 2, 2, "Blank", "Blank", "raw blank", "   ", sha256("   "));
        insertChunk(processed, 3, 3, "NFD", "NFD", "raw nfd", "Cafe\u0301 invalid", sha256("Cafe\u0301 invalid"));
        insertChunk(processed, 4, 4, "Hash", "Hash", "raw hash", "wrong hash searchable", "0".repeat(64));

        assertThat(indexingService.reindexDocument(workspaceId, unsupported).status())
                .isEqualTo(SourceIndexSyncStatus.INELIGIBLE);
        assertThat(indexingService.reindexDocument(workspaceId, failed).status())
                .isEqualTo(SourceIndexSyncStatus.INELIGIBLE);
        SourceIndexSyncResult processedResult = indexingService.reindexDocument(workspaceId, processed);

        assertThat(processedResult.status()).isEqualTo(SourceIndexSyncStatus.SYNCED);
        assertThat(processedResult.eligibleChunkCount()).isEqualTo(1);
        assertThat(ftsRepository.matchSource(workspaceId, "valid searchable"))
                .extracting(SearchIndexMatch::stableId)
                .containsExactly(Long.toString(validChunk));
        assertThat(ftsRepository.matchSource(workspaceId, "unsupported")).isEmpty();
        assertThat(ftsRepository.matchSource(workspaceId, "failed")).isEmpty();
        assertThat(ftsRepository.matchSource(workspaceId, "wrong hash")).isEmpty();
        assertThat(countSourceFts(workspaceId)).isEqualTo(1);
        assertThat(syncStatus(workspaceId, unsupported)).isEqualTo("INELIGIBLE");
        assertThat(syncStatus(workspaceId, failed)).isEqualTo("INELIGIBLE");
        assertThat(db().sql("""
                        SELECT eligible_chunk_count FROM source_search_index_sync
                         WHERE workspace_id = :workspace AND document_id = :document
                        """).param("workspace", workspaceId).param("document", processed)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void documentAndSingleChunkReindexReplaceStaleRowsIdempotently() throws Exception {
        long workspaceId = insertWorkspace("reindex");
        long documentId = insertDocument(workspaceId, "PENDING", "PROCESSED");
        long firstChunk = insertChunk(documentId, 1, 1, "One", "One", "old raw one",
                "old searchable one", sha256("old searchable one"));
        long secondChunk = insertChunk(documentId, 2, 2, "Two", "Two", "old raw two",
                "old searchable two", sha256("old searchable two"));
        indexingService.reindexDocument(workspaceId, documentId);

        db().sql("DELETE FROM source_chunk WHERE id = :id").param("id", secondChunk).update();
        updateChunk(firstChunk, "new searchable one", sha256("new searchable one"));
        SourceIndexSyncResult documentResult = indexingService.reindexDocument(workspaceId, documentId);

        assertThat(documentResult.indexedChunkCount()).isEqualTo(1);
        assertThat(ftsRepository.matchSource(workspaceId, "old searchable")).isEmpty();
        assertThat(ftsRepository.matchSource(workspaceId, "new searchable"))
                .extracting(SearchIndexMatch::stableId).containsExactly(Long.toString(firstChunk));
        assertThat(countSourceFts(workspaceId)).isEqualTo(1);

        updateChunk(firstChunk, "single refresh token", sha256("single refresh token"));
        assertThat(indexingService.reindexSourceChunk(workspaceId, firstChunk).status())
                .isEqualTo(SourceIndexSyncStatus.SYNCED);
        assertThat(ftsRepository.matchSource(workspaceId, "single refresh token")).hasSize(1);
        assertThat(ftsRepository.matchSource(workspaceId, "new searchable")).isEmpty();

        updateChunk(firstChunk, "invalid refresh token", "f".repeat(64));
        assertThat(indexingService.reindexSourceChunk(workspaceId, firstChunk).status())
                .isEqualTo(SourceIndexSyncStatus.INELIGIBLE);
        assertThat(countSourceFts(workspaceId)).isZero();
        assertThat(countSourceIdentity(workspaceId)).isZero();
    }

    @Test
    void projectionFailureRollsBackFtsButPreservesCanonicalEvidenceAndRecordsRepairState() throws Exception {
        long workspaceId = insertWorkspace("failure");
        long documentId = insertDocument(workspaceId, "PENDING", "PROCESSED");
        String raw = "authoritative raw evidence";
        String normalized = "repairable normalized evidence";
        long chunkId = insertChunk(documentId, 1, 1, "Failure", "Failure", raw,
                normalized, sha256(normalized));
        db().sql("""
                CREATE TRIGGER fail_source_identity
                BEFORE INSERT ON search_index_identity
                WHEN NEW.corpus = 'SOURCE'
                BEGIN
                    SELECT RAISE(ABORT, 'simulated Source FTS outage');
                END
                """).update();

        SourceIndexSyncResult failed;
        try {
            failed = indexingService.reindexDocument(workspaceId, documentId);
        } finally {
            db().sql("DROP TRIGGER IF EXISTS fail_source_identity").update();
        }

        assertThat(failed.status()).isEqualTo(SourceIndexSyncStatus.INDEX_PENDING);
        assertThat(failed.detail()).contains("simulated Source FTS outage");
        assertThat(countSourceFts(workspaceId)).isZero();
        assertThat(countSourceIdentity(workspaceId)).isZero();
        assertThat(db().sql("SELECT content || '|' || normalized_content FROM source_chunk WHERE id = :id")
                .param("id", chunkId).query(String.class).single())
                .isEqualTo(raw + "|" + normalized);
        Map<String, Object> pending = db().sql("""
                        SELECT status, eligible_chunk_count, indexed_chunk_count, failure_detail
                          FROM source_search_index_sync
                         WHERE workspace_id = :workspace AND document_id = :document
                        """).param("workspace", workspaceId).param("document", documentId)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "status", rs.getString("status"),
                        "eligible", rs.getInt("eligible_chunk_count"),
                        "indexed", rs.getInt("indexed_chunk_count"),
                        "detail", rs.getString("failure_detail"))).single();
        assertThat(pending).containsEntry("status", "INDEX_PENDING")
                .containsEntry("eligible", 1)
                .containsEntry("indexed", 0);

        SourceIndexSyncResult repaired = indexingService.reindexDocument(workspaceId, documentId);
        assertThat(repaired.status()).isEqualTo(SourceIndexSyncStatus.SYNCED);
        assertThat(ftsRepository.matchSource(workspaceId, "repairable")).hasSize(1);
        assertThat(syncStatus(workspaceId, documentId)).isEqualTo("SYNCED");
    }

    private long insertWorkspace(String suffix) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path,
                            data_path, status, created_at, updated_at)
                        VALUES (:name, :root, :inbox, :archive, :vault, :data, 'ACTIVE',
                            '2026-08-31T00:00:00Z', '2026-08-31T00:00:00Z')
                        """)
                .param("name", "Source index " + suffix)
                .param("root", "/tmp/source-index-" + suffix)
                .param("inbox", "/tmp/source-index-" + suffix + "/inbox")
                .param("archive", "/tmp/source-index-" + suffix + "/archive")
                .param("vault", "/tmp/source-index-" + suffix + "/vault")
                .param("data", "/tmp/source-index-" + suffix + "/data")
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    private long insertDocument(long workspaceId, String status, String parseStatus) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO document (workspace_id, file_name, source_path, sha256, status,
                            parse_status, created_at, updated_at)
                        VALUES (:workspace, :fileName, :sourcePath, :hash, :status, :parseStatus,
                            '2026-08-31T00:00:00Z', '2026-08-31T00:00:00Z')
                        """)
                .param("workspace", workspaceId)
                .param("fileName", "document-" + System.nanoTime() + ".md")
                .param("sourcePath", "inbox/document-" + System.nanoTime() + ".md")
                .param("hash", "a".repeat(64))
                .param("status", status)
                .param("parseStatus", parseStatus)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    private long insertChunk(long documentId, int chunkNo, Integer pageNo, String section,
                             String headingPath, String raw, String normalized, String hash) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO source_chunk (document_id, chunk_no, page_no, section, heading_path,
                            content, normalized_content, content_hash, created_at, updated_at)
                        VALUES (:document, :chunkNo, :pageNo, :section, :headingPath, :raw, :normalized,
                            :hash, '2026-08-31T00:00:00Z', '2026-08-31T00:00:00Z')
                        """)
                .param("document", documentId).param("chunkNo", chunkNo).param("pageNo", pageNo)
                .param("section", section).param("headingPath", headingPath).param("raw", raw)
                .param("normalized", normalized).param("hash", hash).update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    private void updateChunk(long chunkId, String normalized, String hash) {
        db().sql("""
                        UPDATE source_chunk
                           SET normalized_content = :normalized, content_hash = :hash,
                               updated_at = '2026-08-31T01:00:00Z'
                         WHERE id = :id
                        """).param("normalized", normalized).param("hash", hash).param("id", chunkId).update();
    }

    private long identityRowId(long workspaceId, long sourceChunkId) {
        return db().sql("""
                        SELECT fts_rowid FROM search_index_identity
                         WHERE corpus = 'SOURCE' AND workspace_id = :workspace AND stable_id = :stableId
                        """).param("workspace", workspaceId).param("stableId", Long.toString(sourceChunkId))
                .query(Long.class).single();
    }

    private int countSourceIdentity(long workspaceId) {
        return db().sql("SELECT COUNT(*) FROM search_index_identity WHERE corpus = 'SOURCE' AND workspace_id = :id")
                .param("id", workspaceId).query(Integer.class).single();
    }

    private int countSourceFts(long workspaceId) {
        return db().sql("SELECT COUNT(*) FROM source_fts WHERE workspace_id = :id")
                .param("id", workspaceId).query(Integer.class).single();
    }

    private String syncStatus(long workspaceId, long documentId) {
        return db().sql("""
                        SELECT status FROM source_search_index_sync
                         WHERE workspace_id = :workspace AND document_id = :document
                        """).param("workspace", workspaceId).param("document", documentId)
                .query(String.class).single();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
