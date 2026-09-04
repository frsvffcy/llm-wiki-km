package org.km.llmwiki.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class DocumentFileNameBackfillMigrationTest {

    @Test
    void backfillsOriginalFileNameAndNormalizedExtensionForLegacyRows() throws Exception {
        Path databasePath = Path.of("target/test-data/backfill-"
                + UUID.randomUUID() + "/knowledge.db").toAbsolutePath();
        Files.createDirectories(databasePath.getParent());
        String url = "jdbc:sqlite:" + databasePath;
        var dataSource = new DriverManagerDataSource(url);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("2"))
                .load().migrate();

        JdbcClient raw = JdbcClient.create(dataSource);
        long workspaceId = insertWorkspace(raw);
        insertLegacyDocument(raw, workspaceId, "LegacyReport.PDF", "inbox/LegacyReport.PDF");
        insertLegacyDocument(raw, workspaceId, "noext", "inbox/noext");

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .load().migrate();

        var backfilled = raw.sql("""
                        SELECT original_file_name, extension FROM document
                        WHERE file_name = 'LegacyReport.PDF'
                        """)
                .query((rs, rowNum) -> new String[] {
                        rs.getString("original_file_name"),
                        rs.getString("extension")
                })
                .single();
        assertThat(backfilled[0]).isEqualTo("LegacyReport.PDF");
        assertThat(backfilled[1]).isEqualTo("pdf");

        var noExtensionRow = raw.sql("""
                        SELECT original_file_name, extension FROM document
                        WHERE file_name = 'noext'
                        """)
                .query((rs, rowNum) -> new String[] {
                        rs.getString("original_file_name"),
                        rs.getString("extension")
                })
                .single();
        assertThat(noExtensionRow[0]).isEqualTo("noext");
        assertThat(noExtensionRow[1]).isNull();

        Integer stillNullCount = raw.sql("""
                        SELECT COUNT(*) FROM document
                        WHERE original_file_name IS NULL OR (extension IS NULL AND file_name LIKE '%.%')
                        """)
                .query(Integer.class)
                .single();
        assertThat(stillNullCount).isZero();
    }

    private static long insertWorkspace(JdbcClient client) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        client.sql("""
                        INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path,
                            data_path, config_path, status, created_at, updated_at)
                        VALUES ('backfill-test', '/tmp/backfill', '/tmp/backfill/inbox', '/tmp/backfill/archive',
                            '/tmp/backfill/vault', '/tmp/backfill/data', NULL, 'ACTIVE',
                            '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
                        """)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    private static void insertLegacyDocument(JdbcClient client, long workspaceId,
                                             String fileName, String sourcePath) {
        client.sql("""
                        INSERT INTO document (workspace_id, file_name, source_path, sha256,
                            status, created_at, updated_at)
                        VALUES (:workspaceId, :fileName, :sourcePath, 'legacy-hash',
                            'PENDING', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
                        """)
                .param("workspaceId", workspaceId)
                .param("fileName", fileName)
                .param("sourcePath", sourcePath)
                .update();
    }
}
