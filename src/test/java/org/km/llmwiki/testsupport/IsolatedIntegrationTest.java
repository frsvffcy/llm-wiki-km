package org.km.llmwiki.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Resets all application tables before every test method so each test starts from an empty
 * database and does not depend on execution order or state created by other tests.
 */
@SpringIntegrationTest
public abstract class IsolatedIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    @Qualifier("embeddingProjectionTaskExecutor")
    private ThreadPoolTaskExecutor embeddingProjectionTaskExecutor;

    protected final JdbcClient db() {
        return jdbcClient;
    }

    @BeforeEach
    void resetApplicationTables() throws SQLException {
        awaitEmbeddingProjectionTasks();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            DatabaseCleanupPolicy.assertComplete(connection);
            statement.execute("PRAGMA foreign_keys = OFF");
            try {
                for (String table : DatabaseCleanupPolicy.TABLE_DELETE_ORDER) {
                    statement.executeUpdate("DELETE FROM " + table);
                }
            } finally {
                statement.execute("PRAGMA foreign_keys = ON");
            }
        }
    }

    /**
     * The shared SQLite database cannot be reset while an asynchronous embedding job still
     * owns references to the rows being deleted. A FIFO barrier drains all jobs submitted by
     * the preceding test without adding a timing assumption to the isolation contract.
     */
    private void awaitEmbeddingProjectionTasks() {
        try {
            embeddingProjectionTaskExecutor.submit(() -> { }).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while draining embedding projection tasks", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("Embedding projection tasks did not drain before database reset", failure);
        }
    }

    static final class DatabaseCleanupPolicy {

        private static final List<String> TABLE_DELETE_ORDER = List.of(
            "embedding_projection",
            "embedding_projection_operation",
            "embedding_projection_readiness",
            "knowledge_fts",
            "source_fts",
            "search_index_identity",
            "search_index_rebuild_state",
            "source_search_index_sync",
            "knowledge_search_index_sync",
            "wiki_publish_attempt",
            "wiki_publish_operation",
            "wiki_draft",
            "knowledge_page",
            "knowledge_proposal_evidence",
            "knowledge_proposal",
            "knowledge_candidate_evidence",
            "knowledge_candidate",
            "document_analysis",
            "processing_log",
            "processing_job_item",
            "processing_job",
            "source_chunk",
            "document_extracted_content",
            "document",
            "setting_duplicate_backup",
            "setting",
            "workspace"
        );

        /** Migration-owned metadata is immutable test configuration, not per-test state. */
        private static final Set<String> RETAINED_APPLICATION_TABLES = Set.of("search_index_contract");

        private static final Set<String> SYSTEM_TABLES = Set.of("flyway_schema_history", "sqlite_sequence");

        private DatabaseCleanupPolicy() {
        }

        static void assertComplete(Connection connection) throws SQLException {
            Set<String> schemaTables = schemaTables(connection);
            Set<String> missingCleanup = new HashSet<>(TABLE_DELETE_ORDER);
            missingCleanup.removeAll(schemaTables);
            Set<String> uncoveredApplication = uncoveredApplicationTables(schemaTables);
            if (!missingCleanup.isEmpty() || !uncoveredApplication.isEmpty()) {
                throw new IllegalStateException("SQLite cleanup policy is out of date: missing cleanup tables="
                        + missingCleanup + ", uncovered application tables=" + uncoveredApplication);
            }
        }

        static Set<String> uncoveredApplicationTables(Set<String> schemaTables) {
            Set<String> covered = new HashSet<>(TABLE_DELETE_ORDER);
            covered.addAll(RETAINED_APPLICATION_TABLES);
            return schemaTables.stream()
                    .filter(table -> !covered.contains(table))
                    .filter(table -> !SYSTEM_TABLES.contains(table))
                    .filter(table -> !isFtsInternalTable(table))
                    .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        }

        private static Set<String> schemaTables(Connection connection) throws SQLException {
            Set<String> tables = new HashSet<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type = 'table'")) {
                while (result.next()) {
                    tables.add(result.getString(1));
                }
            }
            return tables;
        }

        private static boolean isFtsInternalTable(String table) {
            return table.startsWith("knowledge_fts_") || table.startsWith("source_fts_");
        }
    }
}
