package org.km.llmwiki.search.embedding;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.km.llmwiki.persistence.jooq.generated.Tables.EMBEDDING_PROJECTION;

/** jOOQ-only persistence boundary for the rebuildable embedding projection. */
@Repository
public class EmbeddingProjectionRepository {

    private final DSLContext dsl;

    public EmbeddingProjectionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Transactional(readOnly = true)
    public Optional<StoredEmbeddingProjection> find(long workspaceId, EmbeddingEvidenceKind kind,
                                                     String stableId) {
        return dsl.selectFrom(EMBEDDING_PROJECTION)
                .where(EMBEDDING_PROJECTION.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION.EVIDENCE_KIND.eq(kind.name()))
                .and(EMBEDDING_PROJECTION.STABLE_ID.eq(stableId))
                .fetchOptional(this::map);
    }

    @Transactional(readOnly = true)
    public List<StoredEmbeddingProjection> findAll(long workspaceId) {
        return dsl.selectFrom(EMBEDDING_PROJECTION)
                .where(EMBEDDING_PROJECTION.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .orderBy(EMBEDDING_PROJECTION.EVIDENCE_KIND.asc(), EMBEDDING_PROJECTION.STABLE_ID.asc())
                .fetch(this::map);
    }

    @Transactional
    public void upsertFresh(EmbeddingProjectionIdentity identity, byte[] vectorBlob,
                            String generatedAt) {
        if (vectorBlob == null || vectorBlob.length != identity.dimension() * Double.BYTES) {
            throw new IllegalArgumentException("Fresh projection vector does not match identity dimension");
        }
        if (generatedAt == null || generatedAt.isBlank()) {
            throw new IllegalArgumentException("Fresh projection generatedAt must not be blank");
        }
        // Keep the persistence boundary from accepting a row that would be labelled FRESH but
        // cannot be decoded by the same provider-neutral representation used by freshness checks.
        EmbeddingVectorCodec.decode(identity.canonicalContentHash(), vectorBlob, identity.dimension());
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        dsl.execute("""
                INSERT INTO embedding_projection (
                    workspace_id, evidence_kind, stable_id, canonical_content_hash,
                    embedding_provider, embedding_model, dimension, projection_version,
                    vector_encoding, vector_blob, generation_status, generation_attempt,
                    generated_at, last_attempt_at, failure_type, failure_detail,
                    created_at, updated_at
                ) VALUES ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, 'FRESH',
                          COALESCE((SELECT generation_attempt + 1 FROM embedding_projection
                                    WHERE workspace_id = {0} AND evidence_kind = {1} AND stable_id = {2}), 1),
                          {10}, {11}, NULL, NULL,
                          COALESCE((SELECT created_at FROM embedding_projection
                                    WHERE workspace_id = {0} AND evidence_kind = {1} AND stable_id = {2}), {10}),
                          {11})
                ON CONFLICT (workspace_id, evidence_kind, stable_id) DO UPDATE SET
                    canonical_content_hash = excluded.canonical_content_hash,
                    embedding_provider = excluded.embedding_provider,
                    embedding_model = excluded.embedding_model,
                    dimension = excluded.dimension,
                    projection_version = excluded.projection_version,
                    vector_encoding = excluded.vector_encoding,
                    vector_blob = excluded.vector_blob,
                    generation_status = excluded.generation_status,
                    generation_attempt = excluded.generation_attempt,
                    generated_at = excluded.generated_at,
                    last_attempt_at = excluded.last_attempt_at,
                    failure_type = excluded.failure_type,
                    failure_detail = excluded.failure_detail,
                    updated_at = excluded.updated_at
                """, identity.workspaceId(), identity.evidenceKind().name(), identity.stableId(),
                identity.canonicalContentHash(), identity.embeddingProvider(), identity.embeddingModel(),
                identity.dimension(), identity.projectionVersion(), EmbeddingProjectionContract.VECTOR_ENCODING,
                vectorBlob, generatedAt, now);
    }

    @Transactional
    public void markFailed(long workspaceId, EmbeddingEvidenceKind kind, String stableId,
                           String canonicalContentHash, String failureType, String failureDetail) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        dsl.execute("""
                INSERT INTO embedding_projection (
                    workspace_id, evidence_kind, stable_id, canonical_content_hash,
                    projection_version, generation_status, generation_attempt,
                    last_attempt_at, failure_type, failure_detail, created_at, updated_at
                ) VALUES ({0}, {1}, {2}, {3}, {4}, 'FAILED',
                          COALESCE((SELECT generation_attempt + 1 FROM embedding_projection
                                    WHERE workspace_id = {0} AND evidence_kind = {1} AND stable_id = {2}), 1),
                          {5}, {6}, {7},
                          COALESCE((SELECT created_at FROM embedding_projection
                                    WHERE workspace_id = {0} AND evidence_kind = {1} AND stable_id = {2}), {5}),
                          {5})
                ON CONFLICT (workspace_id, evidence_kind, stable_id) DO UPDATE SET
                    canonical_content_hash = excluded.canonical_content_hash,
                    projection_version = excluded.projection_version,
                    embedding_provider = NULL,
                    embedding_model = NULL,
                    dimension = NULL,
                    vector_encoding = NULL,
                    vector_blob = NULL,
                    generation_status = excluded.generation_status,
                    generation_attempt = excluded.generation_attempt,
                    generated_at = NULL,
                    last_attempt_at = excluded.last_attempt_at,
                    failure_type = excluded.failure_type,
                    failure_detail = excluded.failure_detail,
                    updated_at = excluded.updated_at
                """, workspaceId, kind.name(), stableId, canonicalContentHash,
                EmbeddingProjectionContract.VERSION, now, failureType, failureDetail);
    }

    @Transactional
    public void clearWorkspace(long workspaceId) {
        dsl.deleteFrom(EMBEDDING_PROJECTION)
                .where(EMBEDDING_PROJECTION.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .execute();
    }

    @Transactional
    public void delete(long workspaceId, EmbeddingEvidenceKind kind, String stableId) {
        dsl.deleteFrom(EMBEDDING_PROJECTION)
                .where(EMBEDDING_PROJECTION.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(EMBEDDING_PROJECTION.EVIDENCE_KIND.eq(kind.name()))
                .and(EMBEDDING_PROJECTION.STABLE_ID.eq(stableId))
                .execute();
    }

    /**
     * Removes a Wiki projection using the canonical page identity. Wiki projection stable ids
     * are knowledge ids, not database page ids; the lookup also remains workspace-scoped.
     */
    @Transactional
    public void deleteWikiPage(long workspaceId, long knowledgePageId) {
        String knowledgeId = dsl.select(org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PAGE.KNOWLEDGE_ID)
                .from(org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PAGE)
                .where(org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PAGE.WORKSPACE_ID
                        .eq(Math.toIntExact(workspaceId)))
                .and(org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PAGE.ID
                        .eq(Math.toIntExact(knowledgePageId)))
                .fetchOne(org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_PAGE.KNOWLEDGE_ID);
        if (knowledgeId != null) {
            delete(workspaceId, EmbeddingEvidenceKind.WIKI, knowledgeId);
        }
    }

    private StoredEmbeddingProjection map(org.km.llmwiki.persistence.jooq.generated.tables.records.EmbeddingProjectionRecord row) {
        return new StoredEmbeddingProjection(row.getId().longValue(), row.getWorkspaceId().longValue(),
                EmbeddingEvidenceKind.valueOf(row.getEvidenceKind()), row.getStableId(),
                row.getCanonicalContentHash(), row.getEmbeddingProvider(), row.getEmbeddingModel(),
                row.getDimension(), row.getProjectionVersion(),
                EmbeddingProjectionStatus.valueOf(row.getGenerationStatus()), row.getVectorEncoding(),
                row.getVectorBlob(), row.getGenerationAttempt(), row.getGeneratedAt(), row.getLastAttemptAt(),
                row.getFailureType(), row.getFailureDetail(), row.getCreatedAt(), row.getUpdatedAt());
    }
}
