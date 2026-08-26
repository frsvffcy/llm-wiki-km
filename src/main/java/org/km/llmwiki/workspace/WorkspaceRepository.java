package org.km.llmwiki.workspace;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
                .param("name", record.name())
                .param("rootPath", record.rootPath())
                .param("inboxPath", record.inboxPath())
                .param("archivePath", record.archivePath())
                .param("vaultPath", record.vaultPath())
                .param("dataPath", record.dataPath())
                .param("configPath", record.configPath())
                .param("status", record.status())
                .param("createdAt", record.createdAt())
                .param("updatedAt", record.updatedAt())
                .update(keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Workspace insert did not return a generated id");
        }
        return key.longValue();
    }

    public List<WorkspaceRow> findAll() {
        return jdbcClient.sql("SELECT * FROM workspace ORDER BY created_at DESC")
                .query(WorkspaceRow.class)
                .list();
    }

    public Optional<WorkspaceRow> findById(long id) {
        return jdbcClient.sql("SELECT * FROM workspace WHERE id = :id")
                .param("id", id)
                .query(WorkspaceRow.class)
                .optional();
    }

    public Optional<WorkspaceRow> findActive() {
        return jdbcClient.sql("""
                        SELECT * FROM workspace
                        WHERE status = 'ACTIVE'
                        ORDER BY COALESCE(last_opened_at, created_at) DESC
                        LIMIT 1
                        """)
                .query(WorkspaceRow.class)
                .optional();
    }

    @Transactional
    public void activate(long id) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        jdbcClient.sql("""
                        UPDATE workspace SET status = 'INACTIVE', updated_at = :now
                        WHERE status = 'ACTIVE' AND id <> :id
                        """)
                .param("now", now)
                .param("id", id)
                .update();
        jdbcClient.sql("""
                        UPDATE workspace SET status = 'ACTIVE', last_opened_at = :now, updated_at = :now
                        WHERE id = :id
                        """)
                .param("now", now)
                .param("id", id)
                .update();
    }

    @Transactional
    public void enforceSingleActive() {
        Optional<Long> winnerId = jdbcClient.sql("""
                        SELECT id FROM workspace WHERE status = 'ACTIVE'
                        ORDER BY COALESCE(last_opened_at, created_at) DESC, id DESC
                        LIMIT 1
                        """)
                .query(Long.class)
                .optional();
        if (winnerId.isEmpty()) {
            return;
        }
        jdbcClient.sql("""
                        UPDATE workspace SET status = 'INACTIVE', updated_at = :now
                        WHERE status = 'ACTIVE' AND id <> :id
                        """)
                .param("now", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .param("id", winnerId.get())
                .update();
    }
}
