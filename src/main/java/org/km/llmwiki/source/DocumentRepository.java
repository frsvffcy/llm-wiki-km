package org.km.llmwiki.source;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

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
}
