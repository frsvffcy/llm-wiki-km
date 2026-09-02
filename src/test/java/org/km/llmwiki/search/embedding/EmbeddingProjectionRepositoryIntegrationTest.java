package org.km.llmwiki.search.embedding;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingProjectionRepositoryIntegrationTest extends IsolatedIntegrationTest {

    private static final String HASH = "0123456789abcdef".repeat(4);

    @Autowired
    private EmbeddingProjectionRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void storesSameStableIdentityIndependentlyPerWorkspace() {
        long firstWorkspace = insertWorkspace("first");
        long secondWorkspace = insertWorkspace("second");
        EmbeddingProjectionIdentity first = identity(firstWorkspace);
        EmbeddingProjectionIdentity second = identity(secondWorkspace);

        repository.upsertFresh(first, vectorBlob(1d, 2d), "2026-09-02T00:00:00Z");
        repository.upsertFresh(second, vectorBlob(3d, 4d), "2026-09-02T00:01:00Z");

        assertThat(repository.find(firstWorkspace, EmbeddingEvidenceKind.WIKI, "same-page"))
                .get()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo(EmbeddingProjectionStatus.FRESH);
                    assertThat(row.workspaceId()).isEqualTo(firstWorkspace);
                    assertThat(row.stableId()).isEqualTo("same-page");
                    assertThat(row.dimension()).isEqualTo(2);
                    assertThat(row.generatedAt()).isEqualTo("2026-09-02T00:00:00Z");
                    assertThat(row.generationAttempt()).isEqualTo(1);
                });
        assertThat(repository.find(secondWorkspace, EmbeddingEvidenceKind.WIKI, "same-page"))
                .get()
                .extracting(StoredEmbeddingProjection::workspaceId,
                        StoredEmbeddingProjection::generatedAt)
                .containsExactly(secondWorkspace, "2026-09-02T00:01:00Z");
        assertThat(repository.findAll(firstWorkspace)).hasSize(1);
        assertThat(repository.findAll(secondWorkspace)).hasSize(1);
    }

    @Test
    void failedAttemptClearsVectorMetadataAndCannotLookFresh() {
        long workspaceId = insertWorkspace("failure");
        EmbeddingProjectionIdentity identity = identity(workspaceId);

        repository.upsertFresh(identity, vectorBlob(1d, 2d), "2026-09-02T00:00:00Z");
        repository.markFailed(workspaceId, EmbeddingEvidenceKind.WIKI, "same-page", HASH,
                "TIMEOUT_OR_NETWORK_UNAVAILABLE", "upstream timeout");

        var row = repository.find(workspaceId, EmbeddingEvidenceKind.WIKI, "same-page").orElseThrow();
        assertThat(row.status()).isEqualTo(EmbeddingProjectionStatus.FAILED);
        assertThat(row.vectorBlob()).isNull();
        assertThat(row.embeddingProvider()).isNull();
        assertThat(row.embeddingModel()).isNull();
        assertThat(row.dimension()).isNull();
        assertThat(row.vectorEncoding()).isNull();
        assertThat(row.generatedAt()).isNull();
        assertThat(row.failureType()).isEqualTo("TIMEOUT_OR_NETWORK_UNAVAILABLE");
        assertThat(row.failureDetail()).isEqualTo("upstream timeout");
        assertThat(row.generationAttempt()).isEqualTo(2);
        assertThat(EmbeddingProjectionFreshness.isFresh(row, identity)).isFalse();
    }

    @Test
    void rejectsNonFiniteVectorBeforeWritingAFreshRow() {
        long workspaceId = insertWorkspace("invalid-vector");

        byte[] invalid = ByteBuffer.allocate(2 * Double.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putDouble(Double.NaN)
                .putDouble(2d)
                .array();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.upsertFresh(
                        identity(workspaceId), invalid, "2026-09-02T00:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.findAll(workspaceId)).isEmpty();
    }

    @Test
    void projectionSchemaHasNoCredentialOrRawProviderResponseColumns() {
        List<String> columns = jdbcClient.sql("PRAGMA table_info(embedding_projection)")
                .query((rs, rowNum) -> rs.getString("name"))
                .list();

        assertThat(columns).contains("workspace_id", "evidence_kind", "stable_id",
                "canonical_content_hash", "embedding_provider", "embedding_model", "dimension",
                "projection_version", "vector_blob", "generation_status", "generation_attempt",
                "generated_at", "last_attempt_at", "failure_type", "failure_detail");
        assertThat(columns).noneMatch(column -> column.contains("credential")
                || column.contains("secret") || column.contains("raw_response"));
    }

    @Test
    void clearingOneWorkspaceDoesNotDeleteAnotherWorkspaceProjection() {
        long firstWorkspace = insertWorkspace("clear-first");
        long secondWorkspace = insertWorkspace("clear-second");

        repository.upsertFresh(identity(firstWorkspace), vectorBlob(1d, 2d), "2026-09-02T00:00:00Z");
        repository.upsertFresh(identity(secondWorkspace), vectorBlob(3d, 4d), "2026-09-02T00:00:00Z");
        repository.clearWorkspace(firstWorkspace);

        assertThat(repository.findAll(firstWorkspace)).isEmpty();
        assertThat(repository.findAll(secondWorkspace)).hasSize(1);
    }

    private EmbeddingProjectionIdentity identity(long workspaceId) {
        return new EmbeddingProjectionIdentity(workspaceId, EmbeddingEvidenceKind.WIKI,
                "same-page", HASH, "test-provider", "test-model", 2,
                EmbeddingProjectionContract.VERSION);
    }

    private static byte[] vectorBlob(double first, double second) {
        return EmbeddingVectorCodec.encode(new org.km.llmwiki.ai.embedding.EmbeddingVector(
                org.km.llmwiki.ai.embedding.EmbeddingInput.identityFor("projection input"),
                List.of(first, second)));
    }

    private long insertWorkspace(String suffix) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path,
                            data_path, status, created_at, updated_at)
                        VALUES (:name, :root, :inbox, :archive, :vault, :data, 'ACTIVE',
                            '2026-09-02T00:00:00Z', '2026-09-02T00:00:00Z')
                        """)
                .param("name", "Embedding " + suffix)
                .param("root", "/tmp/embedding-" + suffix)
                .param("inbox", "/tmp/embedding-" + suffix + "/inbox")
                .param("archive", "/tmp/embedding-" + suffix + "/archive")
                .param("vault", "/tmp/embedding-" + suffix + "/vault")
                .param("data", "/tmp/embedding-" + suffix + "/data")
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }
}
