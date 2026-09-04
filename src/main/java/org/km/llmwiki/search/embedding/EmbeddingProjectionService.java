package org.km.llmwiki.search.embedding;

import org.km.llmwiki.ai.embedding.EmbeddingClient;
import org.km.llmwiki.ai.embedding.EmbeddingClientException;
import org.km.llmwiki.ai.embedding.EmbeddingInput;
import org.km.llmwiki.ai.embedding.EmbeddingResult;
import org.km.llmwiki.ai.embedding.EmbeddingVector;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.SourceSearchFreshness;
import org.km.llmwiki.search.SearchCorpus;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rebuilds embedding projections from current authoritative content.
 *
 * <p>Provider calls intentionally happen before the projection transaction. A successful,
 * fully validated result is committed atomically; a typed provider or response failure replaces
 * any prior row with a FAILED row whose vector is null. This keeps a failed attempt from looking
 * fresh while leaving canonical Wiki and Source data untouched.
 */
@Service
public class EmbeddingProjectionService {

    private final PublishedWikiRepository publishedWikiRepository;
    private final PublishedWikiContentReader wikiContentReader;
    private final SourceSearchAuthorityRepository sourceAuthorityRepository;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingProjectionRepository projectionRepository;

    public EmbeddingProjectionService(PublishedWikiRepository publishedWikiRepository,
                                      PublishedWikiContentReader wikiContentReader,
                                      SourceSearchAuthorityRepository sourceAuthorityRepository,
                                      EmbeddingClient embeddingClient,
                                      EmbeddingProjectionRepository projectionRepository) {
        this.publishedWikiRepository = publishedWikiRepository;
        this.wikiContentReader = wikiContentReader;
        this.sourceAuthorityRepository = sourceAuthorityRepository;
        this.embeddingClient = embeddingClient;
        this.projectionRepository = projectionRepository;
    }

    public EmbeddingProjectionResult projectWiki(long workspaceId, long knowledgePageId) {
        return projectWiki(workspaceId, knowledgePageId, 0L);
    }

    public EmbeddingProjectionResult projectWiki(long workspaceId, long knowledgePageId,
                                                 long projectionGeneration) {
        var page = publishedWikiRepository.findPublishedById(workspaceId, knowledgePageId);
        if (page.isEmpty()) {
            projectionRepository.deleteWikiPage(workspaceId, knowledgePageId, projectionGeneration);
            return new EmbeddingProjectionResult(EmbeddingProjectionOperationStatus.NOT_FOUND,
                    workspaceId, EmbeddingEvidenceKind.WIKI, Long.toString(knowledgePageId), null,
                    null, "Published Wiki page was not found in this workspace");
        }

        var published = page.get();
        String stableId = published.knowledgeId();
        String content;
        try {
            content = wikiContentReader.readSearchableContent(published);
        } catch (RuntimeException failure) {
            return failed(workspaceId, EmbeddingEvidenceKind.WIKI, stableId, published.contentHash(), failure,
                    projectionGeneration);
        }
        return generateAndStore(workspaceId, EmbeddingEvidenceKind.WIKI, stableId,
                published.contentHash(), content, projectionGeneration);
    }

    public EmbeddingProjectionResult projectSourceChunk(long workspaceId, long sourceChunkId) {
        return projectSourceChunk(workspaceId, sourceChunkId, 0L);
    }

    public EmbeddingProjectionResult projectSourceChunk(long workspaceId, long sourceChunkId,
                                                        long projectionGeneration) {
        var authority = sourceAuthorityRepository.findDocumentByChunk(workspaceId, sourceChunkId);
        if (authority.isEmpty()) {
            projectionRepository.delete(workspaceId, EmbeddingEvidenceKind.SOURCE_CHUNK,
                    Long.toString(sourceChunkId), projectionGeneration);
            return new EmbeddingProjectionResult(EmbeddingProjectionOperationStatus.NOT_FOUND,
                    workspaceId, EmbeddingEvidenceKind.SOURCE_CHUNK, Long.toString(sourceChunkId), null,
                    null, "Source Chunk was not found in this workspace");
        }

        var document = authority.get();
        var source = SourceSearchFreshness.eligibleDocuments(document).stream()
                .filter(candidate -> candidate.sourceChunkId() == sourceChunkId)
                .findFirst();
        if (source.isEmpty()) {
            projectionRepository.delete(workspaceId, EmbeddingEvidenceKind.SOURCE_CHUNK,
                    Long.toString(sourceChunkId), projectionGeneration);
            return new EmbeddingProjectionResult(EmbeddingProjectionOperationStatus.INELIGIBLE,
                    workspaceId, EmbeddingEvidenceKind.SOURCE_CHUNK, Long.toString(sourceChunkId), null,
                    null, "Source Chunk is not eligible authoritative content");
        }

        var eligible = source.get();
        return generateAndStore(workspaceId, EmbeddingEvidenceKind.SOURCE_CHUNK,
                Long.toString(sourceChunkId), eligible.contentHash(), eligible.normalizedContent(),
                projectionGeneration);
    }

    /** Clears only this workspace's derived rows and rebuilds from current Wiki/Source authority. */
    public EmbeddingProjectionRebuildResult rebuildWorkspace(long workspaceId) {
        projectionRepository.clearWorkspace(workspaceId);
        return rebuildContents(workspaceId, true, true, 0L, 0L);
    }

    /** Generation-aware workspace rebuild; each corpus retains its own ledger generation. */
    public EmbeddingProjectionRebuildResult rebuildWorkspace(long workspaceId, long wikiGeneration,
                                                              long sourceGeneration) {
        projectionRepository.clearCorpus(workspaceId, EmbeddingEvidenceKind.WIKI, wikiGeneration);
        projectionRepository.clearCorpus(workspaceId, EmbeddingEvidenceKind.SOURCE_CHUNK, sourceGeneration);
        return rebuildContents(workspaceId, true, true, wikiGeneration, sourceGeneration);
    }

    /** Rebuilds one corpus without disturbing the other corpus' projection rows. */
    public EmbeddingProjectionRebuildResult rebuildCorpus(long workspaceId, SearchCorpus corpus) {
        if (corpus == null || corpus == SearchCorpus.ALL) {
            return rebuildWorkspace(workspaceId);
        }
        EmbeddingEvidenceKind kind = corpus == SearchCorpus.WIKI
                ? EmbeddingEvidenceKind.WIKI : EmbeddingEvidenceKind.SOURCE_CHUNK;
        projectionRepository.clearCorpus(workspaceId, kind);
        return rebuildContents(workspaceId, corpus == SearchCorpus.WIKI, corpus == SearchCorpus.SOURCE,
                0L, 0L);
    }

    /** Generation-aware corpus rebuild used by the durable processing operation. */
    public EmbeddingProjectionRebuildResult rebuildCorpus(long workspaceId, SearchCorpus corpus,
                                                           long projectionGeneration) {
        if (corpus == null || corpus == SearchCorpus.ALL) {
            throw new IllegalArgumentException("Generation-aware rebuild requires one corpus");
        }
        EmbeddingEvidenceKind kind = corpus == SearchCorpus.WIKI
                ? EmbeddingEvidenceKind.WIKI : EmbeddingEvidenceKind.SOURCE_CHUNK;
        projectionRepository.clearCorpus(workspaceId, kind, projectionGeneration);
        return rebuildContents(workspaceId, corpus == SearchCorpus.WIKI, corpus == SearchCorpus.SOURCE,
                corpus == SearchCorpus.WIKI ? projectionGeneration : 0L,
                corpus == SearchCorpus.SOURCE ? projectionGeneration : 0L);
    }

    private EmbeddingProjectionRebuildResult rebuildContents(long workspaceId, boolean wiki, boolean source,
                                                             long wikiGeneration, long sourceGeneration) {
        int attempted = 0, fresh = 0, failed = 0, ineligible = 0;

        if (wiki) {
            for (var page : publishedWikiRepository.findAllPublished(workspaceId)) {
                attempted++;
                var result = projectWiki(workspaceId, page.id(), wikiGeneration);
                if (result.status() == EmbeddingProjectionOperationStatus.FRESH) {
                    fresh++;
                } else if (result.status() == EmbeddingProjectionOperationStatus.FAILED) {
                    failed++;
                }
            }
        }
        if (source) {
            for (var document : sourceAuthorityRepository.findAllDocuments(workspaceId)) {
                for (var chunk : SourceSearchFreshness.eligibleDocuments(document)) {
                    attempted++;
                    var result = generateAndStore(workspaceId, EmbeddingEvidenceKind.SOURCE_CHUNK,
                            Long.toString(chunk.sourceChunkId()), chunk.contentHash(), chunk.normalizedContent(),
                            sourceGeneration);
                    if (result.status() == EmbeddingProjectionOperationStatus.FRESH) {
                        fresh++;
                    } else {
                        failed++;
                    }
                }
                ineligible += document.chunks().size() - SourceSearchFreshness.eligibleDocuments(document).size();
            }
        }
        return new EmbeddingProjectionRebuildResult(workspaceId, attempted, fresh, failed, ineligible);
    }

    /** Returns eligible chunk identities for one authoritative source document. */
    public java.util.List<Long> sourceChunks(long workspaceId, long documentId) {
        return sourceAuthorityRepository.findAllDocuments(workspaceId).stream()
                .filter(document -> document.documentId() == documentId)
                .flatMap(document -> SourceSearchFreshness.eligibleDocuments(document).stream())
                .map(chunk -> chunk.sourceChunkId()).toList();
    }

    /** Removes source projections whose canonical chunk was deleted, superseded, or ineligible. */
    public int removeOrphanedSourceProjections(long workspaceId) {
        return removeOrphanedSourceProjections(workspaceId, 0L);
    }

    public int removeOrphanedSourceProjections(long workspaceId, long projectionGeneration) {
        int removed = 0;
        for (var projection : projectionRepository.findAll(workspaceId)) {
            if (projection.evidenceKind() != EmbeddingEvidenceKind.SOURCE_CHUNK) continue;
            try {
                long chunkId = Long.parseLong(projection.stableId());
                var authority = sourceAuthorityRepository.findDocumentByChunk(workspaceId, chunkId);
                boolean eligible = authority.isPresent() && SourceSearchFreshness.eligibleDocuments(authority.get())
                        .stream().anyMatch(chunk -> chunk.sourceChunkId() == chunkId);
                if (!eligible) {
                    removed += projectionRepository.delete(workspaceId, EmbeddingEvidenceKind.SOURCE_CHUNK,
                            projection.stableId(), projectionGeneration);
                }
            } catch (NumberFormatException malformed) {
                removed += projectionRepository.delete(workspaceId, EmbeddingEvidenceKind.SOURCE_CHUNK,
                        projection.stableId(), projectionGeneration);
            }
        }
        return removed;
    }

    public void clearWorkspace(long workspaceId) {
        projectionRepository.clearWorkspace(workspaceId);
    }

    public ProjectionMetadata projectionMetadata(long workspaceId, EmbeddingEvidenceKind kind) {
        return projectionMetadata(workspaceId, kind, 0L);
    }

    /**
     * Validates the complete authoritative corpus and creates a snapshot boundary for a target
     * mutation generation. The generation is part of the token even when the corpus is empty.
     */
    public ProjectionMetadata projectionMetadata(long workspaceId, EmbeddingEvidenceKind kind,
                                                 long snapshotGeneration) {
        Map<String, String> authority = authorityHashes(workspaceId, kind);
        List<StoredEmbeddingProjection> rows = projectionRepository.findAll(workspaceId).stream()
                .filter(row -> row.evidenceKind() == kind)
                .toList();

        // A corpus proof is deliberately set-based. It must detect extra, missing, failed,
        // stale, or differently generated rows regardless of database/stable-id ordering.
        if (authority.isEmpty()) {
            if (!rows.isEmpty()) return incompleteMetadata(identityDrifted(rows));
            return new ProjectionMetadata(null, null, null, true, false,
                    snapshotToken(kind, List.of(), snapshotGeneration));
        }
        if (rows.size() != authority.size()) return incompleteMetadata(identityDrifted(rows));

        Map<String, StoredEmbeddingProjection> byId = new HashMap<>();
        for (StoredEmbeddingProjection row : rows) {
            if (row.status() != EmbeddingProjectionStatus.FRESH
                    || row.embeddingProvider() == null || row.embeddingProvider().isBlank()
                    || row.embeddingModel() == null || row.embeddingModel().isBlank()
                    || row.dimension() == null || row.dimension() <= 0
                    || !EmbeddingProjectionContract.VERSION.equals(row.projectionVersion())
                    || byId.put(row.stableId(), row) != null) {
                return incompleteMetadata(identityDrifted(rows));
            }
            // Generation-aware callers must never use a pre-V25 row as evidence for a new
            // proof. Existing generation > 0 rows may legitimately coexist with a newer
            // incremental generation; legacy generation 0 rows may not.
            if (snapshotGeneration > 0 && row.projectionGeneration() <= 0) {
                return incompleteMetadata(identityDrifted(rows));
            }
        }
        if (!byId.keySet().equals(authority.keySet())) return incompleteMetadata(identityDrifted(rows));
        if (rows.stream().anyMatch(row -> !Objects.equals(authority.get(row.stableId()),
                row.canonicalContentHash()))) return incompleteMetadata(identityDrifted(rows));

        StoredEmbeddingProjection first = rows.getFirst();
        if (rows.stream().anyMatch(row -> !Objects.equals(first.embeddingProvider(), row.embeddingProvider())
                || !Objects.equals(first.embeddingModel(), row.embeddingModel())
                || !Objects.equals(first.dimension(), row.dimension()))) {
            return incompleteMetadata(true);
        }
        return new ProjectionMetadata(first.embeddingProvider(), first.embeddingModel(), first.dimension(), true,
                false,
                snapshotToken(kind, rows, snapshotGeneration));
    }

    /**
     * Recomputes corpus counters from current authority and projection rows after an incremental
     * operation. A one-item job must not pretend that its attempted count is the corpus total.
     */
    public ProjectionCounts projectionCounts(long workspaceId, EmbeddingEvidenceKind kind) {
        int expected = switch (kind) {
            case WIKI -> publishedWikiRepository.findAllPublished(workspaceId).size();
            case SOURCE_CHUNK -> sourceAuthorityRepository.findAllDocuments(workspaceId).stream()
                    .mapToInt(document -> SourceSearchFreshness.eligibleDocuments(document).size()).sum();
        };
        int indexed = 0;
        int failed = 0;
        for (var projection : projectionRepository.findAll(workspaceId)) {
            if (projection.evidenceKind() != kind) continue;
            if (projection.status() == EmbeddingProjectionStatus.FRESH) indexed++;
            if (projection.status() == EmbeddingProjectionStatus.FAILED) failed++;
        }
        return new ProjectionCounts(indexed, expected, failed);
    }

    public record ProjectionMetadata(String provider, String model, Integer dimension,
                                     boolean metadataComplete, boolean identityDrifted,
                                     String snapshotToken) {
        public ProjectionMetadata(String provider, String model, Integer dimension) {
            this(provider, model, dimension, true, false, "legacy-completion-proof");
        }
        public ProjectionMetadata(String provider, String model, Integer dimension,
                                  boolean metadataComplete, String snapshotToken) {
            this(provider, model, dimension, metadataComplete, false, snapshotToken);
        }
    }

    public record ProjectionCounts(int indexed, int expected, int failed) {}

    public boolean isFresh(long workspaceId, EmbeddingEvidenceKind kind, String stableId,
                           EmbeddingProjectionIdentity expected) {
        return projectionRepository.find(workspaceId, kind, stableId)
                .filter(projection -> EmbeddingProjectionFreshness.isFresh(projection, expected))
                .isPresent();
    }

    private Map<String, String> authorityHashes(long workspaceId, EmbeddingEvidenceKind kind) {
        Map<String, String> hashes = new HashMap<>();
        if (kind == EmbeddingEvidenceKind.WIKI) {
            publishedWikiRepository.findAllPublished(workspaceId)
                    .forEach(page -> hashes.put(page.knowledgeId(), page.contentHash()));
        } else {
            sourceAuthorityRepository.findAllDocuments(workspaceId).stream()
                    .flatMap(document -> SourceSearchFreshness.eligibleDocuments(document).stream())
                    .forEach(chunk -> hashes.put(Long.toString(chunk.sourceChunkId()), chunk.contentHash()));
        }
        return hashes;
    }

    private static ProjectionMetadata incompleteMetadata(boolean identityDrifted) {
        return new ProjectionMetadata(null, null, null, false, identityDrifted, null);
    }

    private static boolean identityDrifted(List<StoredEmbeddingProjection> rows) {
        List<ProjectionIdentity> identities = rows.stream()
                .filter(row -> row.embeddingProvider() != null && !row.embeddingProvider().isBlank()
                        && row.embeddingModel() != null && !row.embeddingModel().isBlank()
                        && row.dimension() != null && row.dimension() > 0
                        && row.projectionVersion() != null)
                .map(row -> new ProjectionIdentity(row.embeddingProvider(), row.embeddingModel(),
                        row.dimension(), row.projectionVersion()))
                .distinct().toList();
        // A complete row carrying an obsolete projection contract is identity drift even when
        // it is the only row. Without this check it would look merely incomplete and could
        // leave callers with PARTIAL instead of the explicit full-rebuild-required boundary.
        return identities.stream().anyMatch(identity ->
                !EmbeddingProjectionContract.VERSION.equals(identity.projectionVersion()))
                || identities.size() > 1;
    }

    private record ProjectionIdentity(String provider, String model, Integer dimension,
                                      String projectionVersion) {}

    private static String snapshotToken(EmbeddingEvidenceKind kind,
                                       List<StoredEmbeddingProjection> rows,
                                       long snapshotGeneration) {
        List<String> values = new ArrayList<>();
        rows.stream().sorted(Comparator.comparing(StoredEmbeddingProjection::stableId))
                .forEach(row -> values.add(String.join("\u001f", kind.storageValue(), row.stableId(),
                        row.canonicalContentHash(), row.embeddingProvider(), row.embeddingModel(),
                        Integer.toString(row.dimension()), row.projectionVersion(),
                        Long.toString(row.projectionGeneration()))));
        String canonical = "embedding-projection-snapshot-v1\u001e" + kind.storageValue()
                + "\u001e" + Long.toString(snapshotGeneration)
                + "\u001e" + String.join("\u001e", values);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private EmbeddingProjectionResult generateAndStore(long workspaceId, EmbeddingEvidenceKind kind,
                                                       String stableId, String canonicalHash,
                                                       String canonicalContent, long projectionGeneration) {
        try {
            EmbeddingInput input = new EmbeddingInput(canonicalContent);
            EmbeddingResult result = Objects.requireNonNull(embeddingClient.embed(
                    org.km.llmwiki.ai.embedding.EmbeddingRequest.single(input.text())),
                    "Embedding client returned no result");
            EmbeddingVector vector = validateSingleResult(result, input);
            var metadata = result.providerMetadata();
            var identity = new EmbeddingProjectionIdentity(workspaceId, kind, stableId, canonicalHash,
                    metadata.provider(), metadata.model(), vector.values().size(),
                    EmbeddingProjectionContract.VERSION);
            projectionRepository.upsertFresh(identity, EmbeddingVectorCodec.encode(vector), now(),
                    projectionGeneration);
            return new EmbeddingProjectionResult(EmbeddingProjectionOperationStatus.FRESH, workspaceId,
                    kind, stableId, canonicalHash, null, null);
        } catch (EmbeddingClientException failure) {
            return failed(workspaceId, kind, stableId, canonicalHash, failure, projectionGeneration);
        } catch (IllegalArgumentException | NullPointerException failure) {
            return failed(workspaceId, kind, stableId, canonicalHash,
                    new EmbeddingClientException(org.km.llmwiki.ai.embedding.EmbeddingFailureType.INVALID_PROVIDER_RESPONSE,
                            "embedding provider returned an invalid result"), projectionGeneration);
        }
    }

    private static EmbeddingVector validateSingleResult(EmbeddingResult result, EmbeddingInput input) {
        if (result.vectors().size() != 1 || result.vectors().getFirst() == null
                || !input.identity().equals(result.vectors().getFirst().inputIdentity())) {
            throw new IllegalArgumentException("Embedding result does not match the requested input");
        }
        return result.vectors().getFirst();
    }

    private EmbeddingProjectionResult failed(long workspaceId, EmbeddingEvidenceKind kind,
                                             String stableId, String canonicalHash, RuntimeException failure,
                                             long projectionGeneration) {
        String type;
        String detail;
        if (failure instanceof EmbeddingClientException embeddingFailure) {
            type = embeddingFailure.failureType().name();
            detail = embeddingFailure.failure().diagnostic();
        } else {
            type = "AUTHORITATIVE_CONTENT_UNAVAILABLE";
            detail = safeDetail(failure);
        }
        projectionRepository.markFailed(workspaceId, kind, stableId, canonicalHash, type, detail,
                projectionGeneration);
        return new EmbeddingProjectionResult(EmbeddingProjectionOperationStatus.FAILED, workspaceId,
                kind, stableId, canonicalHash, type, detail);
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    private static String safeDetail(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
