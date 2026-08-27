package org.km.llmwiki.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Resets all application tables before every test method so each test starts from an empty
 * database and does not depend on execution order or state created by other tests.
 */
public abstract class IsolatedIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbcClient;

    protected final JdbcClient db() {
        return jdbcClient;
    }

    @BeforeEach
    void resetApplicationTables() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            for (String table : TABLE_DELETE_ORDER) {
                statement.executeUpdate("DELETE FROM " + table);
            }
            statement.execute("PRAGMA foreign_keys = ON");
        }
    }

    private static final List<String> TABLE_DELETE_ORDER = List.of(
            "processing_log",
            "processing_job_item",
            "source_chunk",
            "document_extracted_content",
            "document",
            "setting",
            "workspace"
    );
}
