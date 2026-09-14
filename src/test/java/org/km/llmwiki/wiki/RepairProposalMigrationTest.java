package org.km.llmwiki.wiki;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V31 repair ingress migration (#384): the knowledge_proposal rebuild preserves all
 * rows and descendants, extends the source_kind CHECK with REPAIR, and adds the
 * repair-scoped partial unique dedup index without disturbing the ASK one.
 */
@Tag("integration")
class RepairProposalMigrationTest {

    @Test
    void rebuildPreservesRowsAndEnablesRepairDedup() throws Exception {
        Path databasePath = Path.of("target/test-data/repair-migration-"
                + UUID.randomUUID() + "/knowledge.db").toAbsolutePath();
        Files.createDirectories(databasePath.getParent());
        String url = "jdbc:sqlite:" + databasePath;
        var dataSource = new DriverManagerDataSource(url);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("30"))
                .load().migrate();

        JdbcClient raw = JdbcClient.create(dataSource);
        long workspaceId = insertWorkspace(raw);
        long documentId = insertDocument(raw, workspaceId);
        long chunkId = insertChunk(raw, documentId);
        long analysisProposal = insertProposal(raw, workspaceId, "DOCUMENT_ANALYSIS", null);
        long askProposal = insertProposal(raw, workspaceId, "ASK", "ask-hash-1");
        insertEvidence(raw, analysisProposal, chunkId);
        insertDraft(raw, workspaceId, analysisProposal);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .load().migrate();

        assertThat(raw.sql("SELECT COUNT(*) FROM knowledge_proposal").query(Long.class).single())
                .isEqualTo(2);
        assertThat(raw.sql("SELECT source_kind FROM knowledge_proposal WHERE id = :id")
                .param("id", analysisProposal).query(String.class).single())
                .isEqualTo("DOCUMENT_ANALYSIS");
        assertThat(raw.sql("SELECT COUNT(*) FROM knowledge_proposal_evidence").query(Long.class).single())
                .isEqualTo(1);
        assertThat(raw.sql("SELECT COUNT(*) FROM wiki_draft").query(Long.class).single())
                .isEqualTo(1);
        assertThat(raw.sql("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_knowledge_proposal_%_dedup'")
                .query(String.class).list())
                .containsExactlyInAnyOrder("idx_knowledge_proposal_ask_dedup",
                        "idx_knowledge_proposal_repair_dedup");

        // The extended CHECK accepts REPAIR and rejects anything else.
        raw.sql("""
                        INSERT INTO knowledge_proposal (workspace_id, action, status, provider, model,
                            prompt_identifier, prompt_version, contract_version, normalized_data_json,
                            source_kind, source_dedup_hash, created_at, updated_at)
                        VALUES (:ws, 'MERGE', 'REVIEW', 'vault-lint', 'deterministic-restore-v1',
                            'vault-lint-restore', 'v1', 'v2', '{}', 'REPAIR', 'repair-hash-1', :now, :now)
                        """).param("ws", workspaceId).param("now", "2026-09-01T00:00:00Z").update();
        assertThatThrownBy(() -> raw.sql("""
                        INSERT INTO knowledge_proposal (workspace_id, action, status, provider, model,
                            prompt_identifier, prompt_version, contract_version, normalized_data_json,
                            source_kind, created_at, updated_at)
                        VALUES (:ws, 'MERGE', 'REVIEW', 'p', 'm', 'i', 'v1', 'v2', '{}', 'NOPE', :now, :now)
                        """).param("ws", workspaceId).param("now", "2026-09-01T00:00:00Z").update())
                .hasMessageContaining("CHECK constraint failed");

        // The repair partial index is enforced; the ASK one still works.
        assertThatThrownBy(() -> raw.sql("""
                        INSERT INTO knowledge_proposal (workspace_id, action, status, provider, model,
                            prompt_identifier, prompt_version, contract_version, normalized_data_json,
                            source_kind, source_dedup_hash, created_at, updated_at)
                        VALUES (:ws, 'MERGE', 'REVIEW', 'vault-lint', 'deterministic-restore-v1',
                            'vault-lint-restore', 'v1', 'v2', '{}', 'REPAIR', 'repair-hash-1', :now, :now)
                        """).param("ws", workspaceId).param("now", "2026-09-01T00:00:00Z").update())
                .hasMessageContaining("UNIQUE constraint failed");
        assertThatThrownBy(() -> insertProposal(raw, workspaceId, "ASK", "ask-hash-1"))
                .hasMessageContaining("UNIQUE constraint failed");
        // Same hash under a different kind does not collide (partial indexes are disjoint).
        raw.sql("""
                        INSERT INTO knowledge_proposal (workspace_id, action, status, provider, model,
                            prompt_identifier, prompt_version, contract_version, normalized_data_json,
                            source_kind, source_dedup_hash, created_at, updated_at)
                        VALUES (:ws, 'MERGE', 'REVIEW', 'vault-lint', 'deterministic-restore-v1',
                            'vault-lint-restore', 'v1', 'v2', '{}', 'REPAIR', 'ask-hash-1', :now, :now)
                        """).param("ws", workspaceId).param("now", "2026-09-01T00:00:00Z").update();
    }

    private long insertWorkspace(JdbcClient raw) {
        return raw.sql("""
                        INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path,
                            data_path, status, created_at, updated_at)
                        VALUES ('w', '/tmp/w', '/tmp/w/inbox', '/tmp/w/archive', '/tmp/w/vault',
                            '/tmp/w/data', 'ACTIVE', :now, :now)
                        RETURNING id
                        """).param("now", "2026-09-01T00:00:00Z").query(Long.class).single();
    }

    private long insertDocument(JdbcClient raw, long workspaceId) {
        return raw.sql("""
                        INSERT INTO document (workspace_id, file_name, source_path, sha256, status,
                            created_at, updated_at)
                        VALUES (:ws, 'source.txt', 'source.txt', 'hash', 'PROCESSED', :now, :now)
                        RETURNING id
                        """).param("ws", workspaceId).param("now", "2026-09-01T00:00:00Z")
                .query(Long.class).single();
    }

    private long insertChunk(JdbcClient raw, long documentId) {
        return raw.sql("""
                        INSERT INTO source_chunk (document_id, chunk_no, content, normalized_content,
                            content_hash, created_at, updated_at)
                        VALUES (:document, 1, 'chunk content', 'chunk content', 'chunk-hash', :now, :now)
                        RETURNING id
                        """).param("document", documentId).param("now", "2026-09-01T00:00:00Z")
                .query(Long.class).single();
    }

    private long insertProposal(JdbcClient raw, long workspaceId, String kind, String dedupHash) {
        return raw.sql("""
                        INSERT INTO knowledge_proposal (workspace_id, action, status, provider, model,
                            prompt_identifier, prompt_version, contract_version, normalized_data_json,
                            source_kind, source_dedup_hash, created_at, updated_at)
                        VALUES (:ws, 'MERGE', 'REVIEW', 'p', 'm', 'i', 'v1', 'v2', '{}', :kind,
                            :hash, :now, :now)
                        RETURNING id
                        """).param("ws", workspaceId).param("kind", kind).param("hash", dedupHash)
                .param("now", "2026-09-01T00:00:00Z").query(Long.class).single();
    }

    private void insertEvidence(JdbcClient raw, long proposalId, long chunkId) {
        raw.sql("INSERT INTO knowledge_proposal_evidence (knowledge_proposal_id, source_chunk_id)"
                        + " VALUES (:proposal, :chunk)")
                .param("proposal", proposalId).param("chunk", chunkId).update();
    }

    private void insertDraft(JdbcClient raw, long workspaceId, long proposalId) {
        raw.sql("""
                        INSERT INTO wiki_draft (workspace_id, proposal_id, action, page_type, title,
                            target_title, target_page_type, target_path, status, base_content_hash,
                            rendered_content_hash, input_hash, structured_draft_json, base_content,
                            rendered_content, created_at, updated_at)
                        VALUES (:ws, :proposal, 'CREATE', 'CONCEPT', 'T', 'T', 'CONCEPT',
                            'vault/concepts/t.md', 'DRAFT',
                            '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
                            '1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
                            '2123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
                            '{}', 'base', 'rendered', :now, :now)
                        """).param("ws", workspaceId).param("proposal", proposalId)
                .param("now", "2026-09-01T00:00:00Z").update();
    }
}
