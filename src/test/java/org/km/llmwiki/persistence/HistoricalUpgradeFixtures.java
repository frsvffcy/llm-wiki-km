package org.km.llmwiki.persistence;

import org.flywaydb.core.Flyway;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repository-owned synthetic fixtures for the historical installed-state upgrade matrix. A
 * fixture materializes a real SQLite workspace at a baseline Flyway version by running the
 * actual published migration chain, then inserts deterministic synthetic domain data through
 * the baseline schema (never real user data, never a binary snapshot). Flyway migrations stay
 * the only executable schema authority; this fixture is reviewable setup, not a schema
 * authority.
 */
final class HistoricalUpgradeFixtures {

    static final long ACTIVE_WORKSPACE_ID = 7L;
    static final long OTHER_WORKSPACE_ID = 99L;
    private static final String NOW = "2025-01-01T00:00:00Z";

    /**
     * The actual published vault Markdown for the historical PUBLISHED knowledge page; the
     * page row's canonical content hash is the sha256 of this exact file so the fixture is a
     * self-consistent historical installed state (canonical content reads succeed).
     */
    static final String PUBLISHED_PAGE_MARKDOWN = "---\n"
            + "id: \"wiki-db-lock\"\n"
            + "title: \"資料庫鎖\"\n"
            + "type: \"CONCEPT\"\nstatus: \"PUBLISHED\"\naliases: []\ntags: []\n"
            + "sources: []\ncreated_at: \"" + NOW + "\"\nupdated_at: \"" + NOW + "\"\n"
            + "---\n\n# 資料庫鎖\n\n分散式資料庫的 schema 遷移需要鎖。\n";

    private HistoricalUpgradeFixtures() {
    }

    /** Opens a plain JDBC connection to a fixture database. */
    static Connection open(Path databasePath) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }

    /**
     * Materializes a populated baseline database by running the REAL published migrations up
     * to {@code baselineVersion}, then inserting synthetic populated data through the baseline
     * schema. Layout directories are created so an upgraded workspace can be opened by the
     * application without manual repair.
     */
    static Path materializeBaseline(Path directory, String name, int baselineVersion)
            throws SQLException {
        Path database = directory.resolve(name + "/knowledge.db");
        try {
            Files.createDirectories(directory.resolve(name));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("fixture directory", failure);
        }
        String jdbcUrl = "jdbc:sqlite:" + database;
        Flyway.configure()
                .dataSource(new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                        new org.sqlite.JDBC(), jdbcUrl))
                .locations("classpath:db/migration")
                .target(String.valueOf(baselineVersion))
                .load()
                .migrate();
        Path rootActive = directory.resolve(name).resolve("root");
        Path rootForeign = directory.resolve(name + "-foreign").resolve("root");
        try {
            Path publishedPage = rootActive.resolve("vault/concepts");
            Files.createDirectories(publishedPage);
            Files.writeString(publishedPage.resolve("資料庫鎖.md"), PUBLISHED_PAGE_MARKDOWN);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("fixture vault file", failure);
        }
        for (Path root : List.of(rootActive, rootForeign)) {
            for (String dir : List.of("inbox", "archive", "vault", "data", "config", "logs",
                    "temp")) {
                try {
                    Files.createDirectories(root.resolve(dir));
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException("fixture layout directories", failure);
                }
            }
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }
            populateCanonical(connection, rootActive, rootForeign);
            populateFtsEraRows(connection, baselineVersion, rootActive, rootForeign);
            if (baselineVersion >= 24) {
                populateEmbeddingEraRows(connection, rootActive, rootForeign);
            }
            if (baselineVersion >= 28) {
                populateGraphLifecycleRow(connection, rootActive, rootForeign);
            }
        }
        return database;
    }

    private static String rootLiteral(long workspaceId) {
        return "'<ROOT" + workspaceId + ">'";
    }

    private static void populateCanonical(Connection connection, Path rootActive,
                                          Path rootForeign) throws SQLException {
        execute(connection, rootActive, rootForeign, List.of(
                "INSERT INTO workspace(id, name, root_path, inbox_path, archive_path, vault_path,"
                        + " data_path, config_path, status, created_at, updated_at) VALUES(7, "
                        + "'historical-ws', " + rootLiteral(ACTIVE_WORKSPACE_ID) + ", "
                        + rootLiteral(ACTIVE_WORKSPACE_ID) + "||'/inbox',"
                        + " " + rootLiteral(ACTIVE_WORKSPACE_ID) + "||'/archive',"
                        + " " + rootLiteral(ACTIVE_WORKSPACE_ID) + "||'/vault',"
                        + " " + rootLiteral(ACTIVE_WORKSPACE_ID) + "||'/data',"
                        + " " + rootLiteral(ACTIVE_WORKSPACE_ID) + "||'/config', 'ACTIVE', "
                        + quote(NOW) + ", " + quote(NOW) + ")",
                "INSERT INTO workspace(id, name, root_path, inbox_path, archive_path,"
                        + " vault_path, data_path, config_path, status, created_at, updated_at)"
                        + " VALUES(99, 'other-ws', " + rootLiteral(OTHER_WORKSPACE_ID) + ","
                        + " " + rootLiteral(OTHER_WORKSPACE_ID) + "||'/inbox',"
                        + " " + rootLiteral(OTHER_WORKSPACE_ID) + "||'/archive',"
                        + " " + rootLiteral(OTHER_WORKSPACE_ID) + "||'/vault',"
                        + " " + rootLiteral(OTHER_WORKSPACE_ID) + "||'/data', NULL, 'ACTIVE',"
                        + " " + quote(NOW) + ", " + quote(NOW) + ")",
                "INSERT INTO setting(id, workspace_id, setting_group, setting_key,"
                        + " setting_value, value_type, created_at, updated_at)"
                        + " VALUES(11, 7, 'workspace', 'root.layoutVersion', 'v1', 'STRING',"
                        + " " + quote(NOW) + ", " + quote(NOW) + ")",
                "INSERT INTO document(id, workspace_id, file_name, original_file_name, extension,"
                        + " mime_type, source_path, sha256, file_size, document_type, status,"
                        + " parse_status, processing_status, extracted_text_hash,"
                        + " created_at, updated_at, processed_at)"
                        + " VALUES(1001, 7, 'runbook.md', 'runbook.md', 'md', 'text/markdown',"
                        + " 'inbox/runbook.md',"
                        + " '" + sha256("runbook-v1") + "', 2048, 'MARKDOWN', 'PROCESSED',"
                        + " 'PROCESSED', 'PROCESSED', '" + sha256("runbook-extracted") + "',"
                        + " " + quote(NOW) + ", " + quote(NOW) + ", " + quote(NOW) + ")",
                "INSERT INTO document(id, workspace_id, file_name, original_file_name, extension,"
                        + " mime_type, source_path, sha256, file_size, document_type, status,"
                        + " created_at, updated_at)"
                        + " VALUES(1002, 7, 'superseded.md', 'superseded.md', 'md',"
                        + " 'text/markdown', 'inbox/superseded.md',"
                        + " '" + sha256("superseded") + "', 1024, 'MARKDOWN', 'DELETED', "
                        + quote(NOW) + ", " + quote(NOW) + ")",
                "INSERT INTO document(id, workspace_id, file_name, extension, mime_type,"
                        + " source_path, sha256, file_size, document_type, status,"
                        + " created_at, updated_at)"
                        + " VALUES(1003, 99, 'foreign.md', 'md', 'text/markdown',"
                        + " 'inbox/foreign.md', '"
                        + sha256("foreign") + "', 256, 'MARKDOWN', 'PENDING',"
                        + " " + quote(NOW) + ", " + quote(NOW) + ")",
                "INSERT INTO document_extracted_content(document_id, content, chunk_count,"
                        + " created_at, updated_at) VALUES(1001, 'runbook extraction body', 2,"
                        + " " + quote(NOW) + ", " + quote(NOW) + ")",
                "INSERT INTO source_chunk(id, document_id, chunk_no, page_no, section,"
                        + " heading_path, content, normalized_content, content_hash,"
                        + " created_at, updated_at)"
                        + " VALUES(2001, 1001, 1, 1, '概述', 'Runbook > Overview',"
                        + " '資料庫遷移鎖的第一節內容。', '資料庫遷移鎖的第一節內容。', '"
                        + HistoricalUpgradeFixtures.sha256("資料庫遷移鎖的第一節內容。") + "', " + quote(NOW) + ", " + quote(NOW) + ")",
                "INSERT INTO source_chunk(id, document_id, chunk_no, content, normalized_content,"
                        + " content_hash, created_at, updated_at)"
                        + " VALUES(2002, 1001, 2, '資料庫鎖的第二節內容。', '資料庫鎖的第二節內容。', '"
                        + sha256("資料庫鎖的第二節內容。") + "', " + quote(NOW) + ", " + quote(NOW) + ")",
                "INSERT INTO processing_job(id, workspace_id, job_id, job_type, status,"
                        + " total_count, processed_count, success_count, failed_count,"
                        + " skipped_count, created_at, updated_at)"
                        + " VALUES(3001, 7, 'hist-job-1', 'PROCESS', 'COMPLETED', 1, 1, 1, 0, 0,"
                        + " " + quote(NOW) + ", " + quote(NOW) + ")",
                "INSERT INTO processing_job_item(id, job_id, document_id, status, current_step,"
                        + " retry_count, finished_at)"
                        + " VALUES(3101, 3001, 1001, 'COMPLETED', 'ARCHIVE', 0, " + quote(NOW)
                        + ")",
                "INSERT INTO processing_log(id, job_id, job_item_id, document_id, step, status,"
                        + " message, created_at)"
                        + " VALUES(3201, 3001, 3101, 1001, 'EXTRACT', 'COMPLETED',"
                        + " 'historical extraction step', " + quote(NOW) + ")",
                "INSERT INTO document_analysis(id, job_item_id, document_id, status,"
                        + " prompt_identifier, prompt_version, prompt_content_hash, provider,"
                        + " model, contract_version, result_json, created_at, updated_at)"
                        + " VALUES(4001, 3101, 1001, 'COMPLETED', 'knowledge-analysis', 'v1',"
                        + " '" + sha256("prompt") + "', 'stub', 'offline-model', 'v1',"
                        + " '{}', " + quote(NOW) + ", " + quote(NOW) + ")",
                "INSERT INTO knowledge_candidate(id, document_analysis_id, document_id,"
                        + " candidate_no, title, candidate_type, summary, confidence,"
                        + " rationale, created_at, updated_at)"
                        + " VALUES(5001, 4001, 1001, 1, '資料庫鎖', 'CONCEPT',"
                        + " '資料庫鎖的整理提案', 0.9, 'historical candidate rationale',"
                        + " " + quote(NOW) + ", " + quote(NOW) + ")",
                "INSERT INTO knowledge_proposal(id, workspace_id, document_analysis_id,"
                        + " document_id, knowledge_candidate_id, action, status,"
                        + " provider, model, prompt_identifier, prompt_version,"
                        + " contract_version, normalized_data_json, created_at, updated_at)"
                        + " VALUES(6001, 7, 4001, 1001, 5001, 'CREATE', 'APPROVED',"
                        + " 'stub', 'offline-model', 'knowledge-analysis', 'v1', 'v1',"
                        + " '{}', " + quote(NOW) + ", " + quote(NOW) + ")",
                "INSERT INTO knowledge_candidate_evidence(knowledge_candidate_id,"
                        + " source_chunk_id) VALUES(5001, 2001)",
                "INSERT INTO knowledge_proposal_evidence(knowledge_proposal_id,"
                        + " source_chunk_id) VALUES(6001, 2001)",
                "INSERT INTO wiki_draft(id, workspace_id, proposal_id, action, page_type,"
                        + " title, target_title, target_page_type, target_knowledge_id,"
                        + " target_path, status, base_content_hash, rendered_content_hash,"
                        + " input_hash, structured_draft_json, base_content, rendered_content,"
                        + " created_at, updated_at)"
                        + " VALUES(7001, 7, 6001, 'CREATE', 'CONCEPT', '資料庫鎖', '資料庫鎖',"
                         + " 'CONCEPT', NULL, 'vault/concepts/資料庫鎖.md', 'PUBLISHED', "
                        + " '" + sha256("base") + "', '" + sha256("rendered") + "', '"
                        + sha256("input") + "', '{}', 'base body', 'rendered body',"
                        + " " + quote(NOW) + ", " + quote(NOW) + ")",
                "INSERT INTO knowledge_page(id, workspace_id, knowledge_id, title,"
                        + " normalized_title, type, markdown_path, status, content_hash,"
                        + " revision, proposal_id, draft_id, published_at, created_at, updated_at)"
                        + " VALUES(8001, 7, 'wiki-db-lock', '資料庫鎖', '資料庫鎖', 'CONCEPT',"
                        + " 'vault/concepts/資料庫鎖.md', 'PUBLISHED', '"
                        + sha256(PUBLISHED_PAGE_MARKDOWN) + "', 1, 6001, 7001, " + quote(NOW) + ", "
                        + quote(NOW) + ", "
                        + quote(NOW) + ")",
                "INSERT INTO knowledge_page(id, workspace_id, knowledge_id, title,"
                        + " normalized_title, type, markdown_path, status, content_hash,"
                        + " created_at, updated_at)"
                        + " VALUES(8002, 7, 'wiki-draft-note', '草稿筆記', '草稿筆記', 'HOWTO',"
                        + " 'vault/howto/草稿筆記.md', 'DRAFT', '" + sha256("page2") + "',"
                        + " " + quote(NOW) + ", " + quote(NOW) + ")",
                "INSERT INTO wiki_publish_operation(id, workspace_id, draft_id, proposal_id,"
                        + " action, knowledge_id, target_path, content_hash, revision, status,"
                        + " knowledge_page_id, created_at, updated_at, completed_at)"
                        + " VALUES(9001, 7, 7001, 6001, 'CREATE', 'wiki-db-lock',"
                        + " 'vault/concepts/資料庫鎖.md', '" + sha256(PUBLISHED_PAGE_MARKDOWN)
                        + "', 1,"
                        + " 'COMPLETED', 8001, " + quote(NOW) + ", " + quote(NOW) + ", "
                        + quote(NOW) + ")",
                "INSERT INTO wiki_publish_attempt(id, workspace_id, draft_id, proposal_id,"
                        + " operation_id, action, idempotency_key, target_path,"
                        + " after_content_hash, revision, result, started_at, finished_at)"
                        + " VALUES(9101, 7, 7001, 6001, 9001, 'CREATE', 'hist-attempt-1',"
                        + " 'vault/concepts/資料庫鎖.md', '" + sha256(PUBLISHED_PAGE_MARKDOWN)
                        + "', 1, 'PUBLISHED', " + quote(NOW) + ", " + quote(NOW) + ")"));
    }

    /**
     * FTS-era operational rows (unicode61 projection rows plus sync/health ledgers) that the
     * V19 recreate must invalidate without touching canonical tables.
     */
    private static void populateFtsEraRows(Connection connection, int baselineVersion,
                                            Path rootActive, Path rootForeign) throws SQLException {
        execute(connection, rootActive, rootForeign, List.of(
                "INSERT INTO search_index_identity(corpus, workspace_id, stable_id, fts_rowid,"
                        + " indexed_at) VALUES('KNOWLEDGE', 7, 'wiki-db-lock', 1, " + quote(NOW)
                        + ")",
                "INSERT INTO search_index_identity(corpus, workspace_id, stable_id, fts_rowid,"
                        + " indexed_at) VALUES('SOURCE', 7, '2001', 1, " + quote(NOW) + ")",
                "INSERT INTO knowledge_fts(workspace_id, knowledge_id, title, content,"
                        + " normalized_title, markdown_path, page_type, page_status,"
                        + " content_hash) VALUES(7, 'wiki-db-lock', '資料庫鎖', '資料庫鎖的舊版內容。',"
                        + " '資料庫鎖', 'vault/concepts/資料庫鎖.md', 'CONCEPT', 'PUBLISHED', '"
                        + sha256(PUBLISHED_PAGE_MARKDOWN) + "')",
                "INSERT INTO source_fts(workspace_id, source_chunk_id, document_id, chunk_no,"
                        + " normalized_content, section, heading_path, content_hash)"
                        + " VALUES(7, 2001, 1001, 1, '資料庫鎖的第一節內容。', 'Overview',"
                        + " 'Runbook > Overview', '" + sha256("資料庫遷移鎖的第一節內容。") + "')",
                "INSERT INTO knowledge_search_index_sync(workspace_id, knowledge_page_id,"
                        + " knowledge_id, status, content_hash, indexed_content_hash,"
                        + " indexed_revision, failure_detail, updated_at)"
                        + " VALUES(7, 8001, 'wiki-db-lock', 'SYNCED', '" + sha256(PUBLISHED_PAGE_MARKDOWN)
                        + "', '" + sha256(PUBLISHED_PAGE_MARKDOWN) + "', 1, NULL, " + quote(NOW) + ")",
                "INSERT INTO source_search_index_sync(workspace_id, document_id, status,"
                        + " eligible_chunk_count, indexed_chunk_count, canonical_fingerprint,"
                        + " indexed_fingerprint, updated_at)"
                        + " VALUES(7, 1001, 'SYNCED', 2, 2, '" + sha256("fp-canonical")
                        + "', '" + sha256("fp-indexed") + "', " + quote(NOW) + ")",
                "INSERT INTO search_index_rebuild_state(workspace_id, corpus, status,"
                        + " processing_job_id, indexed_count, failed_count, failure_detail,"
                        + " started_at, completed_at, updated_at)"
                        + " VALUES(7, 'WIKI', 'COMPLETED', 3001, 1, 0, NULL," + quote(NOW)
                        + ", " + quote(NOW) + ", " + quote(NOW) + ")"));
    }

    /**
     * Pre-generation embedding projection rows plus a legacy READY readiness row. The upgrade
     * must preserve them as a legacy baseline without inventing generation history.
     */
    private static void populateEmbeddingEraRows(Connection connection, Path rootActive,
                                                 Path rootForeign) throws SQLException {
        execute(connection, rootActive, rootForeign, List.of(
                "INSERT INTO embedding_projection(id, workspace_id, evidence_kind, stable_id,"
                        + " canonical_content_hash, embedding_provider, embedding_model,"
                        + " dimension, projection_version, vector_encoding, vector_blob,"
                        + " generation_status, generation_attempt, generated_at,"
                        + " last_attempt_at, created_at, updated_at)"
                        + " VALUES(40001, 7, 'WIKI', 'wiki-db-lock', '" + sha256(PUBLISHED_PAGE_MARKDOWN)
                        + "', 'fixture', 'fixture-model', 12, 'fixture-projection-v1',"
                        + " 'FLOAT64_LE', X'DEADBEEF', 'FRESH', 1, " + quote(NOW) + ", "
                        + quote(NOW) + ", " + quote(NOW) + ", " + quote(NOW) + ")",
                "INSERT INTO embedding_projection_readiness(workspace_id, corpus, status,"
                        + " processing_job_id, indexed_count, expected_count, failed_count,"
                        + " embedding_provider, embedding_model, dimension, projection_version,"
                        + " updated_at) VALUES(7, 'WIKI', 'READY', NULL, 1, 1, 0,"
                        + " 'fixture', 'fixture-model', 12, 'fixture-projection-v1', "
                        + quote(NOW) + ")",
                "INSERT INTO embedding_projection_readiness(workspace_id, corpus, status,"
                        + " processing_job_id, indexed_count, expected_count, failed_count,"
                        + " updated_at) VALUES(7, 'SOURCE', 'NOT_BUILT', NULL, 0, 0, 0, "
                        + quote(NOW) + ")"));
    }

    /** A historical graph lifecycle row claims READY, but no provider projection exists. */
    private static void populateGraphLifecycleRow(Connection connection, Path rootActive,
                                                  Path rootForeign) throws SQLException {
        execute(connection, rootActive, rootForeign, List.of(
                "INSERT INTO graph_projection_lifecycle(workspace_id, provider,"
                        + " projection_version, status, target_generation, applied_generation,"
                        + " source_fingerprint, snapshot_token, updated_at)"
                        + " VALUES(7, 'arcadedb', 'graph-projection-v2', 'READY', 1, 1, '"
                        + sha256("graph-fingerprint") + "', '" + sha256("graph-snapshot")
                        + "', " + quote(NOW) + ")"));
    }

    /**
     * Application-owned canonical manifest: stable identity + canonical fields only. Derived
     * projections, vendor identifiers, row order and transient timestamps are deliberately
     * excluded from the equality authority.
     */
    static Map<String, Map<String, String>> canonicalManifest(Connection connection)
            throws SQLException {
        Map<String, Map<String, String>> manifest = new LinkedHashMap<>();
        manifest.putAll(fieldManifest(connection, "workspace", "id",
                List.of("name", "root_path", "status")));
        manifest.putAll(fieldManifest(connection, "setting", "id",
                List.of("workspace_id", "setting_group", "setting_key", "setting_value",
                        "value_type")));
        manifest.putAll(fieldManifest(connection, "document", "id",
                List.of("workspace_id", "file_name", "sha256", "status", "source_path",
                        "document_type")));
        manifest.putAll(fieldManifest(connection, "document_extracted_content", "document_id",
                List.of("content", "chunk_count")));
        manifest.putAll(fieldManifest(connection, "source_chunk", "id",
                List.of("document_id", "chunk_no", "content", "normalized_content",
                        "content_hash", "page_no", "section", "heading_path")));
        manifest.putAll(fieldManifest(connection, "processing_job", "id",
                List.of("workspace_id", "job_id", "job_type", "status", "total_count",
                        "processed_count")));
        manifest.putAll(fieldManifest(connection, "processing_log", "id",
                List.of("job_id", "job_item_id", "document_id", "step", "status", "message")));
        manifest.putAll(fieldManifest(connection, "document_analysis", "id",
                List.of("document_id", "status", "provider", "model", "contract_version")));
        manifest.putAll(fieldManifest(connection, "knowledge_candidate", "id",
                List.of("document_analysis_id", "title", "candidate_type", "summary",
                        "confidence")));
        manifest.putAll(fieldManifest(connection, "knowledge_candidate", "id",
                List.of("document_analysis_id", "title", "candidate_type", "summary",
                        "confidence")));
        manifest.putAll(fieldManifest(connection, "knowledge_candidate_evidence",
                "knowledge_candidate_id",
                List.of("source_chunk_id")));
        manifest.putAll(fieldManifest(connection, "knowledge_proposal", "id",
                List.of("workspace_id", "document_id", "knowledge_candidate_id", "action",
                        "status", "provider", "model", "contract_version")));
        manifest.putAll(fieldManifest(connection, "knowledge_proposal_evidence",
                "knowledge_proposal_id", List.of("source_chunk_id")));
        manifest.putAll(fieldManifest(connection, "wiki_draft", "id",
                List.of("workspace_id", "proposal_id", "action", "title", "status",
                        "rendered_content_hash", "rendered_content")));
        manifest.putAll(fieldManifest(connection, "wiki_publish_operation", "id",
                List.of("draft_id", "proposal_id", "action", "knowledge_id", "target_path",
                        "content_hash", "revision", "status")));
        manifest.putAll(fieldManifest(connection, "wiki_publish_attempt", "id",
                List.of("draft_id", "proposal_id", "operation_id", "action",
                        "idempotency_key", "target_path", "result")));
        manifest.putAll(fieldManifest(connection, "knowledge_page", "id",
                List.of("workspace_id", "knowledge_id", "title", "type", "status",
                        "content_hash", "revision", "markdown_path")));
        return manifest;
    }

    private static Map<String, Map<String, String>> fieldManifest(Connection connection,
                                                                  String table, String idColumn,
                                                                  List<String> fields)
            throws SQLException {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT " + idColumn + ", "
                     + String.join(", ", fields) + " FROM " + table + " ORDER BY " + idColumn)) {
            while (rows.next()) {
                String identity = table + "#" + rows.getLong(1);
                Map<String, String> values = new LinkedHashMap<>();
                for (int index = 0; index < fields.size(); index++) {
                    values.put(fields.get(index), rows.getString(index + 2));
                }
                result.put(identity, values);
            }
        }
        return result;
    }

    /** Stable content hash for fixture payloads (deterministic, reviewable). */
    static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }


    private static String quote(String value) {
        return "'" + value + "'";
    }

    private static void execute(Connection connection, Path rootActive, Path rootForeign,
                                List<String> statements) throws SQLException {
        for (String sql : statements) {
            String resolved = sql
                    .replace("<ROOT7>", rootActive.toString().replace("'", "''"))
                    .replace("<ROOT99>", rootForeign.toString().replace("'", "''"));
            try (PreparedStatement statement = connection.prepareStatement(resolved)) {
                statement.execute();
            } catch (SQLException failure) {
                throw new IllegalStateException("fixture SQL failed: " + resolved, failure);
            }
        }
    }
}
