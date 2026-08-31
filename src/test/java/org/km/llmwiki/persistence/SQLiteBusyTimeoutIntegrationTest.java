package org.km.llmwiki.persistence;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "app.persistence.sqlite.path=target/test-data/busy-${random.uuid}/knowledge.db",
        "app.persistence.sqlite.busy-timeout=2000"
})
class SQLiteBusyTimeoutIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void secondWriterWaitsForConfiguredBusyTimeoutUnderRealLockContention() throws SQLException {
        try (Connection lockHolder = dataSource.getConnection();
             Connection blockedWriter = dataSource.getConnection()) {

            createProbeTable(lockHolder);

            lockHolder.setAutoCommit(false);
            try (Statement statement = lockHolder.createStatement()) {
                statement.execute("INSERT INTO busy_probe (value) VALUES ('lock-holder')");
            }

            long startedAt = System.nanoTime();
            SQLException contentionFailure = null;
            try {
                try (PreparedStatement insert = blockedWriter.prepareStatement(
                        "INSERT INTO busy_probe (value) VALUES ('blocked-writer')")) {
                    insert.executeUpdate();
                }
            } catch (SQLException exception) {
                contentionFailure = exception;
            } finally {
                rollbackQuietly(blockedWriter);
            }
            long waitedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            rollbackQuietly(lockHolder);

            org.assertj.core.api.Assertions.assertThat((Throwable) contentionFailure).isNotNull();
            org.assertj.core.api.Assertions.assertThat(String.valueOf(contentionFailure.getMessage()))
                    .containsIgnoringCase("locked");
            assertThat(Long.valueOf(waitedMillis))
                    .as("blocked writer should wait for the configured busy_timeout of 2000ms")
                    .isBetween(1500L, 30_000L);

            Integer survivingRows = countRowsCommittedBy(lockHolder);
            assertThat(survivingRows).isEqualTo(0);
        }
    }

    private static void createProbeTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS busy_probe (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        value TEXT NOT NULL
                    )
                    """);
        }
    }

    private static Integer countRowsCommittedBy(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM busy_probe")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }
}
