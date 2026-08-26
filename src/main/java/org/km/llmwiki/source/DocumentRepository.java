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
                       Long fileSize, String mimeType, String createdAt, String status,
                       Long duplicateOfDocumentId, Long parentVersionDocumentId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workspaceId", workspaceId)
                .addValue("fileName", fileName)
                .addValue("sourcePath", sourcePath)
                .addValue("sha256", sha256)
                .addValue("fileSize", fileSize)
                .addValue("mimeType", mimeType)
                .addValue("createdAt", createdAt)
                .addValue("status", status)
                .addValue("duplicateOfDocumentId", duplicateOfDocumentId)
                .addValue("parentVersionDocumentId", parentVersionDocumentId);
        jdbcClient.sql("""
                        INSERT INTO document (workspace_id, file_name, source_path, sha256,
                            file_size, mime_type, status, duplicate_of_document_id,
                            parent_version_document_id, created_at, updated_at)
                        VALUES (:workspaceId, :fileName, :sourcePath, :sha256,
                            :fileSize, :mimeType, :status, :duplicateOfDocumentId,
                            :parentVersionDocumentId, :createdAt, :createdAt)
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
                          AND status <> 'DELETED' AND status <> 'SUPERSEDED'
                        ORDER BY id DESC
                        LIMIT 1
                        """)
                .param("workspaceId", workspaceId)
                .param("sourcePath", sourcePath)
                .query((rs, rowNum) -> new DocumentSummary(
                        rs.getLong("id"), rs.getString("source_path"), rs.getString("sha256")))
                .optional();
    }

    public Optional<DocumentSummary> findActiveByWorkspaceAndSha256(long workspaceId, String sha256) {
        return jdbcClient.sql("""
                        SELECT id, source_path, sha256 FROM document
                        WHERE workspace_id = :workspaceId AND sha256 = :sha256
                          AND status <> 'DELETED' AND status <> 'SUPERSEDED'
                        ORDER BY id
                        LIMIT 1
                        """)
                .param("workspaceId", workspaceId)
                .param("sha256", sha256)
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

    public List<DocumentSummary> findInboxManaged(long workspaceId) {
        return jdbcClient.sql("""
                        SELECT id, source_path, sha256 FROM document
                        WHERE workspace_id = :workspaceId AND source_path LIKE 'inbox/%'
                          AND status NOT IN ('DELETED', 'SUPERSEDED', 'ARCHIVED')
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

    public void markSuperseded(long id) {
        jdbcClient.sql("""
                        UPDATE document SET status = 'SUPERSEDED', updated_at = :now WHERE id = :id
                        """)
                .param("now", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .param("id", id)
                .update();
    }
}
