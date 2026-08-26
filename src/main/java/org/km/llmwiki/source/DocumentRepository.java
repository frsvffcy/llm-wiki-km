package org.km.llmwiki.source;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Repository
public class DocumentRepository {

    private final JdbcClient jdbcClient;

    public DocumentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long insert(long workspaceId, String fileName, String sourcePath, String sha256,
                       Long fileSize, String mimeType, String createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workspaceId", workspaceId)
                .addValue("fileName", fileName)
                .addValue("sourcePath", sourcePath)
                .addValue("sha256", sha256)
                .addValue("fileSize", fileSize)
                .addValue("mimeType", mimeType)
                .addValue("createdAt", createdAt);
        jdbcClient.sql("""
                        INSERT INTO document (workspace_id, file_name, source_path, sha256,
                            file_size, mime_type, status, created_at, updated_at)
                        VALUES (:workspaceId, :fileName, :sourcePath, :sha256,
                            :fileSize, :mimeType, 'PENDING', :createdAt, :createdAt)
                        """)
                .paramSource(params)
                .update(keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Document insert did not return a generated id");
        }
        return key.longValue();
    }

    public void deleteById(long id) {
        jdbcClient.sql("DELETE FROM document WHERE id = :id")
                .param("id", id)
                .update();
    }

    public Optional<DocumentSummary> findActiveByWorkspaceAndSourcePath(long workspaceId, String sourcePath) {
        return jdbcClient.sql("""
                        SELECT id, source_path, sha256 FROM document
                        WHERE workspace_id = :workspaceId AND source_path = :sourcePath
                          AND status <> 'DELETED'
                        """)
                .param("workspaceId", workspaceId)
                .param("sourcePath", sourcePath)
                .query((rs, rowNum) -> new DocumentSummary(
                        rs.getLong("id"), rs.getString("source_path"), rs.getString("sha256")))
                .optional();
    }

    public boolean existsByWorkspaceAndSha256(long workspaceId, String sha256) {
        Integer count = jdbcClient.sql("""
                        SELECT COUNT(*) FROM document
                        WHERE workspace_id = :workspaceId AND sha256 = :sha256 AND status <> 'DELETED'
                        """)
                .param("workspaceId", workspaceId)
                .param("sha256", sha256)
                .query(Integer.class)
                .single();
        return count > 0;
    }

    public List<DocumentSummary> findInboxPending(long workspaceId) {
        return jdbcClient.sql("""
                        SELECT id, source_path, sha256 FROM document
                        WHERE workspace_id = :workspaceId AND status = 'PENDING'
                          AND source_path LIKE 'inbox/%'
                        """)
                .param("workspaceId", workspaceId)
                .query((rs, rowNum) -> new DocumentSummary(
                        rs.getLong("id"), rs.getString("source_path"), rs.getString("sha256")))
                .list();
    }

    public void markDeleted(long id) {
        jdbcClient.sql("""
                        UPDATE document SET status = 'DELETED', updated_at = :now WHERE id = :id
                        """)
                .param("now", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .param("id", id)
                .update();
    }
}
