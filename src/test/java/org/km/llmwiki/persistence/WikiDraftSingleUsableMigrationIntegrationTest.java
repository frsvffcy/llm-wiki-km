package org.km.llmwiki.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #601 migration regression：historical duplicate usable drafts 的 deterministic upgrade。
 *
 * <p>以真實 Flyway chain（baseline V34 → latest）升級含重複可用草稿的 installed state：
 * 只保留最新 id 可用，較舊重複列轉 INVALIDATED（內容保留、可稽核；絕不發明 PUBLISHED）；
 * 之後 partial unique index 存在且重複 migrate 冪等。
 */
@Tag("integration")
class WikiDraftSingleUsableMigrationIntegrationTest {

    @TempDir
    Path temp;

    @Test
    void duplicateUsableDraftsUpgradeDeterministicallyWithoutPublishing() throws Exception {
        Path database = HistoricalUpgradeFixtures.materializeBaseline(temp, "dup-usable-draft", 34);
        try (Connection connection = open(database)) {
            insertDraft(connection, 7002, "READY", null);
            insertDraft(connection, 7003, "READY", null);
            insertDraft(connection, 7004, "INVALIDATED", "MANUAL");
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM wiki_draft WHERE workspace_id = 7 AND proposal_id = 6001"
                            + " AND status IN ('DRAFT', 'READY')")).isEqualTo("2");
        }

        migrate(database);
        // 重複 migrate 必須冪等（#322 同例）。
        migrate(database);

        try (Connection connection = open(database)) {
            // 最新 id 存活可用；較舊重複列 deterministic 作廢（內容保留）。
            assertThat(scalar(connection, "SELECT status FROM wiki_draft WHERE id = 7003"))
                    .isEqualTo("READY");
            assertThat(scalar(connection, "SELECT status FROM wiki_draft WHERE id = 7002"))
                    .isEqualTo("INVALIDATED");
            assertThat(scalar(connection,
                    "SELECT invalidated_reason FROM wiki_draft WHERE id = 7002"))
                    .isEqualTo("SUPERSEDED_BY_REGENERATION");
            // 既有 PUBLISHED／INVALIDATED 列不受 dedup 影響。
            assertThat(scalar(connection, "SELECT status FROM wiki_draft WHERE id = 7001"))
                    .isEqualTo("PUBLISHED");
            assertThat(scalar(connection,
                    "SELECT invalidated_reason FROM wiki_draft WHERE id = 7004"))
                    .isEqualTo("MANUAL");
            // 無列遺失、無發明 publish。
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM wiki_draft WHERE workspace_id = 7 AND proposal_id = 6001"))
                    .isEqualTo("4");
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM wiki_draft WHERE workspace_id = 7 AND proposal_id = 6001"
                            + " AND status IN ('DRAFT', 'READY')")).isEqualTo("1");
            assertThat(scalar(connection, "SELECT COUNT(*) FROM wiki_publish_operation"))
                    .isEqualTo("1");
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index'"
                            + " AND name = 'idx_wiki_draft_single_usable_draft'")).isEqualTo("1");
        }
    }

    private void insertDraft(Connection connection, long id, String status, String reason)
            throws SQLException {
        String reasonLiteral = reason == null ? "NULL" : "'" + reason + "'";
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.executeUpdate("INSERT INTO wiki_draft(id, workspace_id, proposal_id, action,"
                    + " page_type, title, target_title, target_page_type, target_path, status,"
                    + " base_content_hash, rendered_content_hash, input_hash, structured_draft_json,"
                    + " base_content, rendered_content, invalidated_reason, created_at, updated_at)"
                    + " VALUES(" + id + ", 7, 6001, 'CREATE', 'CONCEPT', '重複草稿', '重複草稿',"
                    + " 'CONCEPT', 'vault/concepts/dup.md', '" + status + "', 'base', 'rendered',"
                    + " 'input', '{}', 'base body', 'rendered body', " + reasonLiteral + ","
                    + " '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z')");
        }
    }

    private void migrate(Path database) {
        Flyway.configure()
                .dataSource(new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                        new org.sqlite.JDBC(), "jdbc:sqlite:" + database))
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private Connection open(Path database) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             var results = statement.executeQuery(sql)) {
            results.next();
            return results.getString(1);
        }
    }
}
