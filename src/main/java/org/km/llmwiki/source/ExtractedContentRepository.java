package org.km.llmwiki.source;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Repository
public class ExtractedContentRepository {

    private final JdbcClient jdbcClient;

    public ExtractedContentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void save(long documentId, String content, int chunkCount) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        jdbcClient.sql("""
                        INSERT INTO document_extracted_content (
                            document_id, content, chunk_count, created_at, updated_at)
                        VALUES (:documentId, :content, :chunkCount, :now, :now)
                        ON CONFLICT(document_id) DO UPDATE SET
                            content = excluded.content,
                            chunk_count = excluded.chunk_count,
                            updated_at = excluded.updated_at
                        """)
                .param("documentId", documentId)
                .param("content", content)
                .param("chunkCount", chunkCount)
                .param("now", now)
                .update();
    }

    public Optional<ExtractedContentRecord> findByDocumentId(long documentId) {
        return jdbcClient.sql("""
                        SELECT document_id, content, chunk_count
                        FROM document_extracted_content
                        WHERE document_id = :documentId
                        """)
                .param("documentId", documentId)
                .query((rs, rowNum) -> new ExtractedContentRecord(
                        rs.getLong("document_id"), rs.getString("content"), rs.getInt("chunk_count")))
                .optional();
    }

    public void deleteByDocumentId(long documentId) {
        jdbcClient.sql("DELETE FROM document_extracted_content WHERE document_id = :documentId")
                .param("documentId", documentId)
                .update();
    }
}
