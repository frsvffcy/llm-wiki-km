package org.km.llmwiki.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "app.persistence.sqlite.path=target/test-data/setting-${random.uuid}/knowledge.db"
})
class SettingUniquenessIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void rejectsDuplicateGlobalSettingForSameGroupAndKey() {
        JdbcClient client = JdbcClient.create(dataSource);
        insertGlobalSetting(client, "llm", "provider", "openai");

        assertUniqueConstraintViolation(() -> insertGlobalSetting(client, "llm", "provider", "ollama"));

        Integer count = client.sql("""
                        SELECT COUNT(*) FROM setting
                        WHERE workspace_id IS NULL AND setting_group = 'llm' AND setting_key = 'provider'
                        """)
                .query(Integer.class)
                .single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void enforcesWorkspaceScopedUniquenessButAllowsCrossWorkspaceValues() {
        JdbcClient client = JdbcClient.create(dataSource);
        long workspaceA = insertWorkspace(client, "/tmp/settings-a");
        long workspaceB = insertWorkspace(client, "/tmp/settings-b");

        insertWorkspaceSetting(client, workspaceA, "review", "auto_publish", "0");
        insertWorkspaceSetting(client, workspaceB, "review", "auto_publish", "1");

        assertUniqueConstraintViolation(
                () -> insertWorkspaceSetting(client, workspaceA, "review", "auto_publish", "2"));

        Integer rowsForA = client.sql("""
                        SELECT COUNT(*) FROM setting
                        WHERE workspace_id = :workspaceId AND setting_group = 'review' AND setting_key = 'auto_publish'
                        """)
                .param("workspaceId", workspaceA)
                .query(Integer.class)
                .single();
        assertThat(rowsForA).isEqualTo(1);
    }

    @Test
    void globalAndWorkspaceSettingsMayShareSameGroupAndKey() {
        JdbcClient client = JdbcClient.create(dataSource);
        long workspaceId = insertWorkspace(client, "/tmp/settings-shared");

        insertGlobalSetting(client, "review", "confidence_threshold", "0.8");
        insertWorkspaceSetting(client, workspaceId, "review", "confidence_threshold", "0.9");

        Integer totalRows = client.sql("""
                        SELECT COUNT(*) FROM setting
                        WHERE setting_group = 'review' AND setting_key = 'confidence_threshold'
                        """)
                .query(Integer.class)
                .single();
        assertThat(totalRows).isEqualTo(2);
    }

    @Test
    void migrationDeduplicatesPreExistingGlobalRowsIntoBackupTable() throws Exception {
        Path databasePath = Path.of("target/test-data/migration-"
                + UUID.randomUUID() + "/knowledge.db").toAbsolutePath();
        java.nio.file.Files.createDirectories(databasePath.getParent());
        String url = "jdbc:sqlite:" + databasePath;

        Flyway.configure().dataSource(new org.springframework.jdbc.datasource.DriverManagerDataSource(url))
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("1"))
                .load().migrate();

        JdbcClient raw = JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource(url));
        for (int index = 0; index < 3; index++) {
            raw.sql("""
                            INSERT INTO setting (workspace_id, setting_group, setting_key, setting_value,
                                value_type, created_at, updated_at)
                            VALUES (NULL, 'llm', 'model', :value, 'STRING',
                                '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
                            """)
                    .param("value", "model-v" + index)
                    .update();
        }

        Flyway.configure().dataSource(new org.springframework.jdbc.datasource.DriverManagerDataSource(url))
                .locations("classpath:db/migration")
                .load().migrate();

        Integer remainingGlobals = raw.sql("""
                        SELECT COUNT(*) FROM setting
                        WHERE workspace_id IS NULL AND setting_group = 'llm' AND setting_key = 'model'
                        """)
                .query(Integer.class)
                .single();
        assertThat(remainingGlobals).isEqualTo(1);

        String survivingValue = raw.sql("""
                        SELECT setting_value FROM setting
                        WHERE workspace_id IS NULL AND setting_group = 'llm' AND setting_key = 'model'
                        """)
                .query(String.class)
                .single();
        assertThat(survivingValue).isEqualTo("model-v0");

        Integer backupRows = raw.sql("SELECT COUNT(*) FROM setting_duplicate_backup")
                .query(Integer.class)
                .single();
        assertThat(backupRows).isEqualTo(2);

        var backupValues = raw.sql("""
                        SELECT setting_value FROM setting_duplicate_backup ORDER BY id
                        """)
                .query(String.class)
                .list();
        assertThat(backupValues).containsExactly("model-v1", "model-v2");
    }

    private static void insertGlobalSetting(JdbcClient client, String group, String key, String value) {
        client.sql("""
                        INSERT INTO setting (workspace_id, setting_group, setting_key, setting_value,
                            value_type, created_at, updated_at)
                        VALUES (NULL, :group, :key, :value, 'STRING',
                            '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
                        """)
                .param("group", group)
                .param("key", key)
                .param("value", value)
                .update();
    }

    private static void insertWorkspaceSetting(JdbcClient client, long workspaceId,
                                               String group, String key, String value) {
        client.sql("""
                        INSERT INTO setting (workspace_id, setting_group, setting_key, setting_value,
                            value_type, created_at, updated_at)
                        VALUES (:workspaceId, :group, :key, :value, 'STRING',
                            '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
                        """)
                .param("workspaceId", workspaceId)
                .param("group", group)
                .param("key", key)
                .param("value", value)
                .update();
    }

    private static void assertUniqueConstraintViolation(Runnable insertion) {
        Throwable failure = catchThrowable(insertion::run);
        assertThat(failure).isInstanceOf(RuntimeException.class);
        assertThat(String.valueOf(failure.getCause())).contains("UNIQUE constraint failed");
    }

    private static long insertWorkspace(JdbcClient client, String rootPath) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        client.sql("""
                        INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path,
                            data_path, config_path, status, created_at, updated_at)
                        VALUES ('settings-test', :rootPath, '/tmp/inbox', '/tmp/archive', '/tmp/vault',
                            '/tmp/data', NULL, 'ACTIVE', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
                        """)
                .param("rootPath", rootPath)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }
}
