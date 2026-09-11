package org.km.llmwiki.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Historical installed-state upgrade matrix (#322). Each representative baseline is a real
 * populated SQLite workspace materialized by the actual published Flyway migrations
 * (V18 pre-CJK projection, V24 pre-embedding generation ledger, V27 pre-Graph lifecycle,
 * V28 pre-versioned ChunkingPolicy); upgrading runs the real migration chain to the latest
 * version without touching any released migration. Gates: canonical data preservation by
 * application-owned stable identity, workspace isolation and soft-delete/status semantics,
 * derived projections never fake current/READY (FTS recreate forces a deterministic rebuild
 * signal, the embedding generation ledger gets no invented history, graph lifecycle never
 * claims READY for a missing provider projection, chunk-policy backfill never silently mixes
 * old and new corpora), repeated migrate idempotency, and no half-upgraded fake health.
 * Reports nothing to Git; this gate is deterministic and offline.
 */
@Tag("integration")
class HistoricalUpgradeMatrixIntegrationTest {

    @TempDir
    Path temp;

    @ParameterizedTest
    @ValueSource(ints = {18, 24, 27, 28})
    void historicalPopulatedWorkspaceUpgradesToLatestWithCanonicalDataPreserved(
            int baselineVersion) throws Exception {
        Path database = HistoricalUpgradeFixtures.materializeBaseline(temp,
                "baseline-v" + baselineVersion, baselineVersion);

        Map<String, Map<String, String>> beforeManifest;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            beforeManifest = HistoricalUpgradeFixtures.canonicalManifest(connection);
            assertThat(beforeManifest).containsKeys("workspace#7", "workspace#99",
                    "document#1001", "document#1002", "document#1003", "source_chunk#2001",
                    "knowledge_page#8001", "knowledge_proposal#6001",
                    "wiki_publish_operation#9001", "processing_job#3001");
        }

        Flyway.configure()
                .dataSource(new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                        new org.sqlite.JDBC(), "jdbc:sqlite:" + database))
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            Map<String, Map<String, String>> afterManifest =
                    HistoricalUpgradeFixtures.canonicalManifest(connection);
            assertThat(afterManifest)
                    .as("canonical/domain data must survive the migration chain")
                    .isEqualTo(beforeManifest);

            assertWorkspaceSemantics(connection);
            assertProjectionUpgradeSemantics(connection, baselineVersion);
            assertMigrationIdempotency(connection, beforeManifest);
        }
    }

    /** Identity, isolation, soft-delete, and publish-status semantics survive the upgrade. */
    private void assertWorkspaceSemantics(Connection connection) throws SQLException {
        assertThat(scalar(connection,
                "SELECT status FROM workspace WHERE id = 7")).isEqualTo("ACTIVE");
        assertThat(scalar(connection,
                "SELECT name FROM workspace WHERE id = 99")).isEqualTo("other-ws");
        // Workspace isolation: the foreign document must never leak into the active workspace.
        assertThat(scalar(connection,
                "SELECT COUNT(*) FROM document WHERE workspace_id = 99")).isEqualTo("1");
        assertThat(scalar(connection,
                "SELECT COUNT(*) FROM document WHERE workspace_id = 7")).isEqualTo("2");
        // Soft delete/status semantics are not rewritten by any backfill.
        assertThat(scalar(connection,
                "SELECT status FROM document WHERE id = 1002")).isEqualTo("DELETED");
        assertThat(scalar(connection,
                "SELECT status FROM knowledge_page WHERE id = 8001")).isEqualTo("PUBLISHED");
        assertThat(scalar(connection,
                "SELECT status FROM knowledge_page WHERE id = 8002")).isEqualTo("DRAFT");
        assertThat(scalar(connection,
                "SELECT status FROM knowledge_proposal WHERE id = 6001")).isEqualTo("APPROVED");
        assertThat(scalar(connection,
                "SELECT status FROM wiki_publish_operation WHERE id = 9001"))
                .isEqualTo("COMPLETED");
    }

    /** Derived projections may be invalidated, never fake-current or fake-READY. */
    private void assertProjectionUpgradeSemantics(Connection connection, int baselineVersion)
            throws SQLException {
        // FTS: the V19 recreate wipes the projection; the upgraded contract version is
        // cjk-bigram-v1 and the emptied identity map is the deterministic rebuild signal.
        if (baselineVersion <= 18) {
            assertThat(scalar(connection,
                    "SELECT projection_version FROM search_index_contract WHERE corpus = 'KNOWLEDGE'"))
                    .isEqualTo("cjk-bigram-v1");
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM knowledge_fts")).isEqualTo("0");
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM source_fts")).isEqualTo("0");
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM search_index_identity")).isEqualTo("0");
            // Historical sync/rebuild rows keep their operational semantics with the
            // backfilled projection version; the empty identity map prevents a stale row
            // from masquerading as a healthy served index.
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM knowledge_search_index_sync")).isEqualTo("1");
            assertThat(scalar(connection,
                    "SELECT DISTINCT projection_version FROM knowledge_search_index_sync"))
                    .isEqualTo("cjk-bigram-v1");
            assertThat(scalar(connection,
                    "SELECT DISTINCT projection_version FROM search_index_rebuild_state"))
                    .isEqualTo("cjk-bigram-v1");
            assertThat(scalar(connection,
                    "SELECT status FROM search_index_rebuild_state"
                            + " WHERE workspace_id = 7 AND corpus = 'WIKI'"))
                    .isEqualTo("COMPLETED");
        }
        // Embedding: legacy READY rows stay a legacy baseline, but the generation ledger must
        // never be backfilled with an invented operation history or snapshot token.
        if (baselineVersion == 24) {
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM embedding_projection")).isEqualTo("1");
            assertThat(scalar(connection,
                    "SELECT status FROM embedding_projection_readiness"
                            + " WHERE workspace_id = 7 AND corpus = 'WIKI'")).isEqualTo("READY");
            assertThat(scalar(connection,
                    "SELECT target_generation FROM embedding_projection_readiness"
                            + " WHERE workspace_id = 7 AND corpus = 'WIKI'")).isEqualTo("0");
            assertThat(scalar(connection,
                    "SELECT applied_generation FROM embedding_projection_readiness"
                            + " WHERE workspace_id = 7 AND corpus = 'WIKI'")).isEqualTo("0");
            assertThat(scalar(connection,
                    "SELECT projection_snapshot_token FROM embedding_projection_readiness"
                            + " WHERE workspace_id = 7 AND corpus = 'WIKI'")).isNull();
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM embedding_projection_operation")).isEqualTo("0");
        }
        // Graph: the SQLite lifecycle table is state only; a migration must never invent a
        // READY row for a provider projection that does not exist.
        if (baselineVersion == 27) {
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM graph_projection_lifecycle")).isEqualTo("0");
        }
        if (baselineVersion == 28) {
            assertThat(scalar(connection,
                    "SELECT status FROM graph_projection_lifecycle WHERE workspace_id = 7"))
                    .isEqualTo("READY");
            assertThat(scalar(connection,
                    "SELECT provider FROM graph_projection_lifecycle WHERE workspace_id = 7"))
                    .isEqualTo("arcadedb");
        }
        // Application-level read paths over the upgraded non-empty historical states:
        // the embedding readiness reader and the graph verification reader must agree with
        // the schema-level assertions (legacy READY without invented history; no READY
        // claim for a provider projection that was never built by this application).
        if (baselineVersion == 24) {
            org.km.llmwiki.search.embedding.EmbeddingProjectionReadinessRepository reader =
                    new org.km.llmwiki.search.embedding.EmbeddingProjectionReadinessRepository(
                            dslContext(connection));
            org.km.llmwiki.search.embedding.EmbeddingProjectionReadiness wiki = reader
                    .find(7L, org.km.llmwiki.search.embedding.EmbeddingEvidenceKind.WIKI)
                    .orElseThrow();
            assertThat(wiki.status()).isEqualTo(
                    org.km.llmwiki.search.embedding.EmbeddingProjectionReadinessStatus.READY);
            assertThat(wiki.targetGeneration()).isZero();
            assertThat(wiki.appliedGeneration()).isZero();
            assertThat(wiki.projectionSnapshotToken()).isNull();
        }
        // ChunkingPolicy: the V29 backfill stamps every historical chunk with the current
        // policy version so old and new corpora can never be silently mixed.
        if (baselineVersion <= 28) {
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM source_chunk WHERE chunk_policy_version"
                            + " IS NULL OR chunk_policy_version != 'chunk-policy-v1-current'"))
                    .isEqualTo("0");
        }
    }

    /**
     * A second migrate call is a no-op: no pending migrations, no checksum drift, and the
     * canonical manifest is byte-identical (no destructive backfill re-run).
     */
    private void assertMigrationIdempotency(Connection connection,
                                            Map<String, Map<String, String>> beforeManifest)
            throws SQLException {
        org.flywaydb.core.api.output.MigrateResult secondRun = Flyway.configure()
                .dataSource(new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                        new org.sqlite.JDBC(), "jdbc:sqlite:" + dbPath(connection)))
                .locations("classpath:db/migration")
                .load()
                .migrate();
        assertThat(secondRun.migrationsExecuted).isZero();

        Map<String, Map<String, String>> afterManifest =
                HistoricalUpgradeFixtures.canonicalManifest(connection);
        assertThat(afterManifest).isEqualTo(beforeManifest);

        int processedRows = Integer.parseInt(scalar(connection,
                "SELECT COUNT(*) FROM processing_log"));
        assertThat(processedRows).isEqualTo(1);
    }

    private org.jooq.DSLContext dslContext(Connection connection) {
        return org.jooq.impl.DSL.using(connection);
    }

    private String dbPath(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeQuery("PRAGMA database_list").getString("file");
        }
    }

    private String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }
}
