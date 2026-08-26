package com.frsvffcy.llmwiki.workspace;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

@Repository
public class WorkspaceRepository {

    private final JdbcClient jdbcClient;

    public WorkspaceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<Long> findIdByRootPath(String normalizedRootPath) {
        return jdbcClient.sql("SELECT id FROM workspace WHERE root_path = :rootPath")
                .param("rootPath", normalizedRootPath)
                .query(Long.class)
                .optional();
    }

    public long insert(WorkspaceRecord record) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path,
                            data_path, config_path, status, created_at, updated_at)
                        VALUES (:name, :rootPath, :inboxPath, :archivePath, :vaultPath,
                            :dataPath, :configPath, :status, :createdAt, :updatedAt)
                        """)
                .params(Map.of(
                        "name", record.name(),
                        "rootPath", record.rootPath(),
                        "inboxPath", record.inboxPath(),
                        "archivePath", record.archivePath(),
                        "vaultPath", record.vaultPath(),
                        "dataPath", record.dataPath(),
                        "configPath", record.configPath(),
                        "status", record.status(),
                        "createdAt", record.createdAt(),
                        "updatedAt", record.updatedAt()))
                .update(keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Workspace insert did not return a generated id");
        }
        return key.longValue();
    }
}
