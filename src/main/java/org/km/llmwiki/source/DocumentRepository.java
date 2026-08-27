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

    public long insert(long workspaceId, String fileName, String originalFileName, String extension,
                       String sourcePath, String sha256, Long fileSize, String mimeType, String createdAt,
                       String status, Long duplicateOfDocumentId, Long parentVersionDocumentId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workspaceId", workspaceId)
                .addValue("fileName", fileName)
                .addValue("originalFileName", originalFileName)
                .addValue("extension", extension)
                .addValue("sourcePath", sourcePath)
                .addValue("sha256", sha256)
                .addValue("fileSize", fileSize)
                .addValue("mimeType", mimeType)
                .addValue("createdAt", createdAt)
                .addValue("status", status)
                .addValue("duplicateOfDocumentId", duplicateOfDocumentId)
                .addValue("parentVersionDocumentId", parentVersionDocumentId);
        jdbcClient.sql("""
                        INSERT INTO document (workspace_id, file_name, original_file_name, extension,
                            source_path, sha256, file_size, mime_type, status,
                            duplicate_of_document_id, parent_version_document_id, created_at, updated_at)
                        VALUES (:workspaceId, :fileName, :originalFileName, :extension,
                            :sourcePath, :sha256, :fileSize, :mimeType, :status,
                            :duplicateOfDocumentId, :parentVersionDocumentId, :createdAt, :createdAt)
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

    public Optional<DocumentExtractionTarget> findExtractionTarget(long workspaceId, long documentId) {
        return jdbcClient.sql("""
                        SELECT id, file_name, mime_type, source_path, parse_status
                        FROM document
                        WHERE workspace_id = :workspaceId AND id = :documentId
                          AND status NOT IN ('DELETED', 'SUPERSEDED')
                        """)
                .param("workspaceId", workspaceId)
                .param("documentId", documentId)
                .query((rs, rowNum) -> new DocumentExtractionTarget(
                        rs.getLong("id"),
                        rs.getString("file_name"),
                        rs.getString("mime_type"),
                        rs.getString("source_path"),
                        rs.getString("parse_status")))
                .optional();
    }

    public void markExtractionSucceeded(long documentId, String extractedTextHash) {
        jdbcClient.sql("""
                        UPDATE document
                        SET parse_status = :parseStatus, extracted_text_hash = :extractedTextHash,
                            error_code = NULL, error_message = NULL, updated_at = :now
                        WHERE id = :id
                        """)
                .param("parseStatus", DocumentStatus.PROCESSED.name())
                .param("extractedTextHash", extractedTextHash)
                .param("now", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .param("id", documentId)
                .update();
    }

    public void markExtractionFailed(long documentId, DocumentStatus parseStatus,
                                     String errorCode, String errorMessage) {
        jdbcClient.sql("""
                        UPDATE document
                        SET parse_status = :parseStatus, extracted_text_hash = NULL,
                            error_code = :errorCode, error_message = :errorMessage, updated_at = :now
                        WHERE id = :id
                        """)
                .param("parseStatus", parseStatus.name())
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .param("now", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .param("id", documentId)
                .update();
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

    public long countInboxDocuments(long workspaceId, String statusFilter, String extensionFilter) {
        return jdbcClient.sql(inboxQuery("COUNT(*)", statusFilter, extensionFilter, null))
                .paramSource(inboxParams(workspaceId, statusFilter, extensionFilter))
                .query(Long.class)
                .single();
    }

    public List<InboxDocumentRow> findInboxDocuments(long workspaceId, String statusFilter,
                                                     String extensionFilter, String orderBy, int limit, int offset) {
        String sql = inboxQuery("""
                id AS document_id, file_name,
                COALESCE(original_file_name, file_name) AS original_file_name,
                extension, mime_type, file_size, status, parse_status, created_at""", statusFilter, extensionFilter, orderBy)
                + " LIMIT :limit OFFSET :offset";
        return jdbcClient.sql(sql)
                .paramSource(inboxParams(workspaceId, statusFilter, extensionFilter)
                        .addValue("limit", limit)
                        .addValue("offset", offset))
                .query((rs, rowNum) -> new InboxDocumentRow(
                        rs.getLong("document_id"),
                        rs.getString("file_name"),
                        rs.getString("original_file_name"),
                        rs.getString("extension"),
                        rs.getString("mime_type"),
                        rs.getObject("file_size") == null ? null : rs.getLong("file_size"),
                        rs.getString("status"),
                        rs.getString("parse_status"),
                        rs.getString("created_at")))
                .list();
    }

    public Optional<InboxDocumentRow> findInboxDocument(long workspaceId, long documentId) {
        return jdbcClient.sql("""
                        SELECT id AS document_id, file_name,
                            COALESCE(original_file_name, file_name) AS original_file_name,
                            extension, mime_type, file_size, status, parse_status, created_at
                        FROM document
                        WHERE workspace_id = :workspaceId AND id = :documentId
                          AND source_path LIKE 'inbox/%'
                          AND status NOT IN ('DELETED', 'SUPERSEDED', 'ARCHIVED')
                        """)
                .param("workspaceId", workspaceId)
                .param("documentId", documentId)
                .query((rs, rowNum) -> new InboxDocumentRow(
                        rs.getLong("document_id"),
                        rs.getString("file_name"),
                        rs.getString("original_file_name"),
                        rs.getString("extension"),
                        rs.getString("mime_type"),
                        rs.getObject("file_size") == null ? null : rs.getLong("file_size"),
                        rs.getString("status"),
                        rs.getString("parse_status"),
                        rs.getString("created_at")))
                .optional();
    }

    private static org.springframework.jdbc.core.namedparam.MapSqlParameterSource inboxParams(
            long workspaceId, String statusFilter, String extensionFilter) {
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("workspaceId", workspaceId);
        if (statusFilter != null) {
            params.addValue("status", statusFilter);
        }
        if (extensionFilter != null) {
            params.addValue("extension", extensionFilter);
        }
        return params;
    }

    private static String inboxQuery(Object select, String statusFilter, String extensionFilter, String orderBy) {
        StringBuilder sql = new StringBuilder("SELECT ").append(select).append(" FROM document")
                .append(" WHERE workspace_id = :workspaceId")
                .append(" AND source_path LIKE 'inbox/%'")
                .append(" AND status NOT IN ('DELETED', 'SUPERSEDED', 'ARCHIVED')");
        if (statusFilter != null) {
            sql.append(" AND status = :status");
        }
        if (extensionFilter != null) {
            sql.append(" AND extension = :extension");
        }
        if (orderBy != null && !orderBy.isBlank()) {
            sql.append(" ORDER BY ").append(orderBy);
        }
        return sql.toString();
    }

    public Optional<DocumentDeletionView> findDeletionView(long workspaceId, long documentId) {
        return jdbcClient.sql("""
                        SELECT id AS document_id, source_path, status FROM document
                        WHERE workspace_id = :workspaceId AND id = :documentId
                        """)
                .param("workspaceId", workspaceId)
                .param("documentId", documentId)
                .query((rs, rowNum) -> new DocumentDeletionView(
                        rs.getLong("document_id"),
                        rs.getString("source_path"),
                        rs.getString("status")))
                .optional();
    }

    public List<DocumentSummary> findCurrentDuplicatesOf(long workspaceId, long canonicalDocumentId) {
        return jdbcClient.sql("""
                        SELECT id, source_path, sha256 FROM document
                        WHERE workspace_id = :workspaceId
                          AND duplicate_of_document_id = :canonicalId
                          AND status = 'DUPLICATE'
                        ORDER BY id
                        """)
                .param("workspaceId", workspaceId)
                .param("canonicalId", canonicalDocumentId)
                .query((rs, rowNum) -> new DocumentSummary(
                        rs.getLong("id"), rs.getString("source_path"), rs.getString("sha256")))
                .list();
    }

    public void promoteDuplicateToCanonical(long duplicateDocumentId) {
        jdbcClient.sql("""
                        UPDATE document SET status = 'PENDING', duplicate_of_document_id = NULL,
                            updated_at = :now
                        WHERE id = :id
                        """)
                .param("now", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .param("id", duplicateDocumentId)
                .update();
    }

    public void repointDuplicates(long previousCanonicalId, long newCanonicalId) {
        jdbcClient.sql("""
                        UPDATE document SET duplicate_of_document_id = :newCanonicalId,
                            updated_at = :now
                        WHERE duplicate_of_document_id = :previousCanonicalId
                          AND status = 'DUPLICATE'
                        """)
                .param("newCanonicalId", newCanonicalId)
                .param("previousCanonicalId", previousCanonicalId)
                .param("now", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .update();
    }
}
