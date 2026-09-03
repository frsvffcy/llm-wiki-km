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
        var page = publishedWikiRepository.findPublishedById(workspaceId, knowledgePageId);
        if (page.isEmpty()) {
            projectionRepository.deleteWikiPage(workspaceId, knowledgePageId);
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
            return failed(workspaceId, EmbeddingEvidenceKind.WIKI, stableId, published.contentHash(), failure);
        }
        return generateAndStore(workspaceId, EmbeddingEvidenceKind.WIKI, stableId,
                published.contentHash(), content);
    }

    public EmbeddingProjectionResult projectSourceChunk(long workspaceId, long sourceChunkId) {
        var authority = sourceAuthorityRepository.findDocumentByChunk(workspaceId, sourceChunkId);
        if (authority.isEmpty()) {
            projectionRepository.delete(workspaceId, EmbeddingEvidenceKind.SOURCE_CHUNK,
                    Long.toString(sourceChunkId));
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
                    Long.toString(sourceChunkId));
            return new EmbeddingProjectionResult(EmbeddingProjectionOperationStatus.INELIGIBLE,
                    workspaceId, EmbeddingEvidenceKind.SOURCE_CHUNK, Long.toString(sourceChunkId), null,
                    null, "Source Chunk is not eligible authoritative content");
        }

        var eligible = source.get();
        return generateAndStore(workspaceId, EmbeddingEvidenceKind.SOURCE_CHUNK,
                Long.toString(sourceChunkId), eligible.contentHash(), eligible.normalizedContent());
    }

    /** Clears only this workspace's derived rows and rebuilds from current Wiki/Source authority. */
    public EmbeddingProjectionRebuildResult rebuildWorkspace(long workspaceId) {
        projectionRepository.clearWorkspace(workspaceId);
        return rebuildContents(workspaceId, true, true);
    }

    /** Rebuilds one corpus without disturbing the other corpus' projection rows. */
    public EmbeddingProjectionRebuildResult rebuildCorpus(long workspaceId, SearchCorpus corpus) {
        if (corpus == null || corpus == SearchCorpus.ALL) {
            return rebuildWorkspace(workspaceId);
        }
        EmbeddingEvidenceKind kind = corpus == SearchCorpus.WIKI
                ? EmbeddingEvidenceKind.WIKI : EmbeddingEvidenceKind.SOURCE_CHUNK;
        projectionRepository.clearCorpus(workspaceId, kind);
        return rebuildContents(workspaceId, corpus == SearchCorpus.WIKI, corpus == SearchCorpus.SOURCE);
    }

    private EmbeddingProjectionRebuildResult rebuildContents(long workspaceId, boolean wiki, boolean source) {
        int attempted = 0, fresh = 0, failed = 0, ineligible = 0;

        if (wiki) {
            for (var page : publishedWikiRepository.findAllPublished(workspaceId)) {
                attempted++;
                var result = projectWiki(workspaceId, page.id());
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
                            Long.toString(chunk.sourceChunkId()), chunk.contentHash(), chunk.normalizedContent());
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
        int removed = 0;
        for (var projection : projectionRepository.findAll(workspaceId)) {
            if (projection.evidenceKind() != EmbeddingEvidenceKind.SOURCE_CHUNK) continue;
            try {
                long chunkId = Long.parseLong(projection.stableId());
                var authority = sourceAuthorityRepository.findDocumentByChunk(workspaceId, chunkId);
                boolean eligible = authority.isPresent() && SourceSearchFreshness.eligibleDocuments(authority.get())
                        .stream().anyMatch(chunk -> chunk.sourceChunkId() == chunkId);
                if (!eligible) {
                    projectionRepository.delete(workspaceId, EmbeddingEvidenceKind.SOURCE_CHUNK, projection.stableId());
                    removed++;
                }
            } catch (NumberFormatException malformed) {
                projectionRepository.delete(workspaceId, EmbeddingEvidenceKind.SOURCE_CHUNK, projection.stableId());
                removed++;
            }
        }
        return removed;
    }

    public void clearWorkspace(long workspaceId) {
        projectionRepository.clearWorkspace(workspaceId);
    }

    public ProjectionMetadata projectionMetadata(long workspaceId, EmbeddingEvidenceKind kind) {
        return projectionRepository.findAll(workspaceId).stream()
                .filter(row -> row.evidenceKind() == kind && row.status() == EmbeddingProjectionStatus.FRESH)
                .findFirst()
                .map(row -> new ProjectionMetadata(row.embeddingProvider(), row.embeddingModel(), row.dimension()))
                .orElse(new ProjectionMetadata(null, null, null));
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
        return new ProjectionCounts(indexed, expected, Math.min(failed, expected));
    }

    public record ProjectionMetadata(String provider, String model, Integer dimension) {}

    public record ProjectionCounts(int indexed, int expected, int failed) {}

    public boolean isFresh(long workspaceId, EmbeddingEvidenceKind kind, String stableId,
                           EmbeddingProjectionIdentity expected) {
        return projectionRepository.find(workspaceId, kind, stableId)
                .filter(projection -> EmbeddingProjectionFreshness.isFresh(projection, expected))
                .isPresent();
    }

    private EmbeddingProjectionResult generateAndStore(long workspaceId, EmbeddingEvidenceKind kind,
                                                       String stableId, String canonicalHash,
                                                       String canonicalContent) {
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
            projectionRepository.upsertFresh(identity, EmbeddingVectorCodec.encode(vector), now());
            return new EmbeddingProjectionResult(EmbeddingProjectionOperationStatus.FRESH, workspaceId,
                    kind, stableId, canonicalHash, null, null);
        } catch (EmbeddingClientException failure) {
            return failed(workspaceId, kind, stableId, canonicalHash, failure);
        } catch (IllegalArgumentException | NullPointerException failure) {
            return failed(workspaceId, kind, stableId, canonicalHash,
                    new EmbeddingClientException(org.km.llmwiki.ai.embedding.EmbeddingFailureType.INVALID_PROVIDER_RESPONSE,
                            "embedding provider returned an invalid result"));
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
                                             String stableId, String canonicalHash, RuntimeException failure) {
        String type;
        String detail;
        if (failure instanceof EmbeddingClientException embeddingFailure) {
            type = embeddingFailure.failureType().name();
            detail = embeddingFailure.failure().diagnostic();
        } else {
            type = "AUTHORITATIVE_CONTENT_UNAVAILABLE";
            detail = safeDetail(failure);
        }
        projectionRepository.markFailed(workspaceId, kind, stableId, canonicalHash, type, detail);
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
