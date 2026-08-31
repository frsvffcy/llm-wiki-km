package org.km.llmwiki.persistence;

import org.flywaydb.core.Flyway;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void appliesCoreTablesToEmptyDatabase() {
        assertThat(tableExists("workspace")).isTrue();
        assertThat(tableExists("setting")).isTrue();
        assertThat(tableExists("document")).isTrue();
        assertThat(tableExists("processing_job")).isTrue();
        assertThat(tableExists("processing_job_item")).isTrue();
        assertThat(tableExists("processing_log")).isTrue();
        assertThat(tableExists("document_analysis")).isTrue();
        assertThat(tableExists("knowledge_candidate")).isTrue();
        assertThat(tableExists("knowledge_candidate_evidence")).isTrue();
        assertThat(tableExists("knowledge_proposal")).isTrue();
        assertThat(tableExists("knowledge_proposal_evidence")).isTrue();
        assertThat(tableExists("search_index_rebuild_state")).isTrue();
        assertThat(tableExists("flyway_schema_history")).isTrue();
    }

    @Test
    void recordsSuccessfulMigrationHistory() {
        Integer applied = jdbcClient.sql("""
                        SELECT COUNT(*) FROM flyway_schema_history
                        WHERE version = '1' AND success = 1
                        """)
                .query(Integer.class)
                .single();
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void addsRetryEligibilityToProcessingJobItems() {
        var columnNames = jdbcClient.sql("PRAGMA table_info(processing_job_item)")
                .query((resultSet, rowNum) -> resultSet.getString("name"))
                .list();

        assertThat(columnNames).contains("retry_eligible");
    }

    @Test
    void addsIndexedRevisionToWikiSearchSyncLedger() {
        var columnNames = jdbcClient.sql("PRAGMA table_info(knowledge_search_index_sync)")
                .query((resultSet, rowNum) -> resultSet.getString("name"))
                .list();

        assertThat(columnNames).contains("indexed_revision");
    }

    @Test
    void secondMigrateDoesNotReapply() {
        Integer before = historyCount();
        int appliedAgain = flyway.migrate().migrationsExecuted;
        Integer after = historyCount();

        assertThat(appliedAgain).isZero();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void documentForeignKeysReferenceWorkspaceAndSelf() {
        Long workspaceId = insertAndReturnId("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path,
                    data_path, config_path, status, created_at, updated_at)
                VALUES ('fk-test', '/tmp/km', '/tmp/km/inbox', '/tmp/km/archive', '/tmp/km/vault',
                    '/tmp/km/data', NULL, 'ACTIVE', '2026-08-26T00:00:00Z', '2026-08-26T00:00:00Z')
                """);

        Long duplicateTargetId = insertAndReturnId("""
                INSERT INTO document (workspace_id, file_name, source_path, sha256, created_at, updated_at)
                VALUES (:workspaceId, 'a.pdf', 'inbox/a.pdf', 'hash-a',
                    '2026-08-26T00:00:00Z', '2026-08-26T00:00:00Z')
                """, Map.of("workspaceId", workspaceId));

        Long duplicateId = insertAndReturnId("""
                INSERT INTO document (workspace_id, file_name, source_path, sha256,
                    duplicate_of_document_id, created_at, updated_at)
                VALUES (:workspaceId, 'a-copy.pdf', 'inbox/a-copy.pdf', 'hash-a',
                    :duplicateOf, '2026-08-26T00:00:00Z', '2026-08-26T00:00:00Z')
                """, Map.of(
                "workspaceId", workspaceId,
                "duplicateOf", duplicateTargetId));

        assertThat(duplicateId).isNotNull().isNotEqualTo(duplicateTargetId);
    }

    @Test
    void processingJobCascadeDeletesItemsAndLogs() {
        Long workspaceId = insertAndReturnId("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path,
                    data_path, config_path, status, created_at, updated_at)
                VALUES ('cascade-test', '/tmp/cascade', '/tmp/cascade/inbox', '/tmp/cascade/archive',
                    '/tmp/cascade/vault', '/tmp/cascade/data', NULL, 'ACTIVE',
                    '2026-08-26T00:00:00Z', '2026-08-26T00:00:00Z')
                """);

        Long documentId = insertAndReturnId("""
                INSERT INTO document (workspace_id, file_name, source_path, sha256, created_at, updated_at)
                VALUES (:workspaceId, 'b.pdf', 'inbox/b.pdf', 'hash-b',
                    '2026-08-26T00:00:00Z', '2026-08-26T00:00:00Z')
                """, Map.of("workspaceId", workspaceId));

        Long jobId = insertAndReturnId("""
                INSERT INTO processing_job (workspace_id, job_id, job_type, created_at, updated_at)
                VALUES (:workspaceId, 'JOB-TEST-CASCADE', 'PROCESS',
                    '2026-08-26T00:00:00Z', '2026-08-26T00:00:00Z')
                """, Map.of("workspaceId", workspaceId));

        Long itemId = insertAndReturnId("""
                INSERT INTO processing_job_item (job_id, document_id)
                VALUES (:jobId, :documentId)
                """, Map.of("jobId", jobId, "documentId", documentId));

        jdbcClient.sql("""
                        INSERT INTO processing_log (job_id, job_item_id, document_id, step, status, created_at)
                        VALUES (:jobId, :itemId, :documentId, 'DISCOVER', 'SUCCESS', '2026-08-26T00:00:00Z')
                        """)
                .param("jobId", jobId)
                .param("itemId", itemId)
                .param("documentId", documentId)
                .update();

        jdbcClient.sql("DELETE FROM processing_job WHERE id = :jobId")
                .param("jobId", jobId)
                .update();

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM processing_job_item WHERE job_id = :jobId")
                .param("jobId", jobId)
                .query(Integer.class)
                .single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM processing_log WHERE job_id = :jobId")
                .param("jobId", jobId)
                .query(Integer.class)
                .single()).isZero();
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcClient.sql(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = :name")
                .param("name", tableName)
                .query(Integer.class)
                .single();
        return count == 1;
    }

    private Integer historyCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM flyway_schema_history")
                .query(Integer.class)
                .single();
    }

    private Long insertAndReturnId(String sql) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql(sql).update(keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    private Long insertAndReturnId(String sql, Map<String, Object> params) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql(sql).params(params).update(keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }
}
