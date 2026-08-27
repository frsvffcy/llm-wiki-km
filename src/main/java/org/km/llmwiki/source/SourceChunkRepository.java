package org.km.llmwiki.source;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Repository
public class SourceChunkRepository {

    private final JdbcClient jdbcClient;

    public SourceChunkRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void replaceForDocument(long documentId, List<SourceChunkDraft> chunks) {
        deleteByDocumentId(documentId);
        if (chunks.isEmpty()) {
            return;
        }

        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        for (SourceChunkDraft chunk : chunks) {
            jdbcClient.sql("""
                            INSERT INTO source_chunk (
                                document_id, chunk_no, page_no, section, heading_path,
                                content, normalized_content, content_hash, created_at, updated_at)
                            VALUES (
                                :documentId, :chunkNo, :pageNo, :section, :headingPath,
                                :content, :normalizedContent, :contentHash, :now, :now)
                            """)
                    .param("documentId", documentId)
                    .param("chunkNo", chunk.chunkNo())
                    .param("pageNo", chunk.pageNo())
                    .param("section", chunk.section())
                    .param("headingPath", chunk.headingPath())
                    .param("content", chunk.content())
                    .param("normalizedContent", chunk.normalizedContent())
                    .param("contentHash", chunk.contentHash())
                    .param("now", now)
                    .update();
        }
    }

    public List<SourceChunk> findByDocumentId(long documentId) {
        return jdbcClient.sql("""
                        SELECT id, document_id, chunk_no, page_no, section, heading_path,
                               content, normalized_content, content_hash
                        FROM source_chunk
                        WHERE document_id = :documentId
                        ORDER BY chunk_no
                        """)
                .param("documentId", documentId)
                .query((rs, rowNum) -> sourceChunk(rs))
                .list();
    }

    public Optional<SourceChunk> findByIdAndWorkspaceId(long chunkId, long workspaceId) {
        return jdbcClient.sql("""
                        SELECT source_chunk.id, source_chunk.document_id, source_chunk.chunk_no,
                               source_chunk.page_no, source_chunk.section, source_chunk.heading_path,
                               source_chunk.content, source_chunk.normalized_content, source_chunk.content_hash
                        FROM source_chunk
                        JOIN document ON document.id = source_chunk.document_id
                        WHERE source_chunk.id = :chunkId AND document.workspace_id = :workspaceId
                          AND document.status NOT IN ('DELETED', 'SUPERSEDED')
                        """)
                .param("chunkId", chunkId)
                .param("workspaceId", workspaceId)
                .query((rs, rowNum) -> sourceChunk(rs))
                .optional();
    }

    public void deleteByDocumentId(long documentId) {
        jdbcClient.sql("DELETE FROM source_chunk WHERE document_id = :documentId")
                .param("documentId", documentId)
                .update();
    }

    private static SourceChunk sourceChunk(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new SourceChunk(
                resultSet.getLong("id"),
                resultSet.getLong("document_id"),
                resultSet.getInt("chunk_no"),
                resultSet.getObject("page_no", Integer.class),
                resultSet.getString("section"),
                resultSet.getString("heading_path"),
                resultSet.getString("content"),
                resultSet.getString("normalized_content"),
                resultSet.getString("content_hash"));
    }
}
