package org.km.llmwiki.search.embedding;

import org.km.llmwiki.ai.embedding.EmbeddingClient;
import org.km.llmwiki.ai.embedding.EmbeddingClientException;
import org.km.llmwiki.ai.embedding.EmbeddingInput;
import org.km.llmwiki.ai.embedding.EmbeddingResult;
import org.km.llmwiki.ai.embedding.EmbeddingVector;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.SourceSearchFreshness;
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
        int attempted = 0;
        int fresh = 0;
        int failed = 0;
        int ineligible = 0;

        for (var page : publishedWikiRepository.findAllPublished(workspaceId)) {
            attempted++;
            var result = projectWiki(workspaceId, page.id());
            if (result.status() == EmbeddingProjectionOperationStatus.FRESH) {
                fresh++;
            } else if (result.status() == EmbeddingProjectionOperationStatus.FAILED) {
                failed++;
            }
        }
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
        return new EmbeddingProjectionRebuildResult(workspaceId, attempted, fresh, failed, ineligible);
    }

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
