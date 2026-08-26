package org.km.llmwiki.persistence;

import org.km.llmwiki.config.SQLiteProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "app.persistence.sqlite.path=target/test-data/${random.uuid}/knowledge.db"
})
class SQLiteConnectionIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private SQLiteConnectionProbe connectionProbe;

    @Autowired
    private SQLiteProperties properties;

    @BeforeEach
    void createTransactionProbeTable() {
        jdbcClient.sql("DROP TABLE IF EXISTS story002_transaction_probe").update();
        jdbcClient.sql("""
                CREATE TABLE story002_transaction_probe (
                    id INTEGER PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """).update();
    }

    @Test
    void createsDatabaseAndExecutesBasicQuery() {
        assertThat(connectionProbe.isReachable()).isTrue();
        assertThat(Files.isRegularFile(properties.getPath().toAbsolutePath())).isTrue();
    }

    @Test
    void appliesPragmasToEveryConnection() throws SQLException {
        try (Connection first = dataSource.getConnection();
             Connection second = dataSource.getConnection()) {
            assertPragmas(first);
            assertPragmas(second);
        }
    }

    @Test
    void commitsAndRollsBackTransactions() {
        transactionTemplate.executeWithoutResult(status -> jdbcClient.sql(
                        "INSERT INTO story002_transaction_probe (value) VALUES (:value)")
                .param("value", "committed")
                .update());

        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("INSERT INTO story002_transaction_probe (value) VALUES (:value)")
                    .param("value", "rolled-back")
                    .update();
            status.setRollbackOnly();
        });

        assertThat(jdbcClient.sql("SELECT value FROM story002_transaction_probe ORDER BY id")
                .query(String.class)
                .list()).containsExactly("committed");
    }

    private static void assertPragmas(Connection connection) throws SQLException {
        assertThat(queryInt(connection, "PRAGMA foreign_keys")).isEqualTo(1);
        assertThat(queryString(connection, "PRAGMA journal_mode")).isEqualToIgnoringCase("wal");
        assertThat(queryInt(connection, "PRAGMA busy_timeout")).isEqualTo(5000);
        assertThat(queryInt(connection, "PRAGMA synchronous")).isEqualTo(1);
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.getInt(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.getString(1);
        }
    }
}
