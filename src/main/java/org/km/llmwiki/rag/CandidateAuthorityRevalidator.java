package org.km.llmwiki.rag;

import org.jooq.exception.DataAccessException;
import org.km.llmwiki.search.SearchCandidate;
import org.km.llmwiki.search.SourceSearchAuthorityChunk;
import org.km.llmwiki.search.SourceSearchAuthorityDocument;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.SourceSearchEligibilityPolicy;
import org.km.llmwiki.search.SourceSearchFreshness;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.PublishedWikiUnavailableException;
import org.km.llmwiki.wiki.PublishedWikiValidationException;
import org.km.llmwiki.wiki.StoredPublishedWiki;

import java.util.Map;
import java.util.Optional;

/**
 * Shared candidate-to-authority revalidation boundary. Providers find candidates; this class
 * re-reads canonical Wiki/Source authority and only returns evidence material whose revision,
 * hashes, and eligibility still match. Used by {@link RetrievalService} and modality fusion so
 * every evidence channel passes the identical authority contract.
 */
final class CandidateAuthorityRevalidator {

    private final PublishedWikiRepository publishedWikiRepository;
    private final PublishedWikiContentReader publishedWikiContentReader;
    private final SourceSearchAuthorityRepository sourceAuthorityRepository;

    CandidateAuthorityRevalidator(PublishedWikiRepository publishedWikiRepository,
                                  PublishedWikiContentReader publishedWikiContentReader,
                                  SourceSearchAuthorityRepository sourceAuthorityRepository) {
        this.publishedWikiRepository = publishedWikiRepository;
        this.publishedWikiContentReader = publishedWikiContentReader;
        this.sourceAuthorityRepository = sourceAuthorityRepository;
    }

    RevalidationOutcome revalidate(SearchCandidate candidate, long workspaceId,
            Map<Long, Optional<SourceSearchAuthorityDocument>> sourceDocuments) {
        if (candidate.workspace() == null || candidate.workspace().id() != workspaceId) {
            return RevalidationOutcome.rejected(AuthorityRejectionReason.WORKSPACE_MISMATCH);
        }
        return switch (candidate.kind()) {
            case WIKI -> revalidateWiki(candidate, workspaceId);
            case SOURCE_CHUNK -> revalidateSource(candidate, workspaceId, sourceDocuments);
        };
    }

    /** Terminal publication check: DB-side authority must still match the evidence at publish time. */
    PublicationOutcome publicationCurrent(EvidenceItem item, long workspaceId,
            Map<Long, Optional<SourceSearchAuthorityDocument>> sourceDocuments) {
        return switch (item.kind()) {
            case WIKI -> wikiPublicationCurrent(item, workspaceId);
            case SOURCE_CHUNK -> sourcePublicationCurrent(item, workspaceId, sourceDocuments);
        };
    }

    private RevalidationOutcome revalidateWiki(SearchCandidate candidate, long workspaceId) {
        if (candidate.knowledgeId() == null
                || !candidate.stableId().equals(candidate.knowledgeId())) {
            return RevalidationOutcome.rejected(AuthorityRejectionReason.IDENTITY_MISMATCH);
        }
        Optional<StoredPublishedWiki> stored;
        try {
            stored = publishedWikiRepository.findPublishedByKnowledgeId(
                    workspaceId, candidate.stableId());
        } catch (DataAccessException infrastructureFailure) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.WIKI_AUTHORITY,
                    infrastructureFailure);
        }
        if (stored.isEmpty()) {
            return RevalidationOutcome.rejected(AuthorityRejectionReason.AUTHORITY_MISSING);
        }
        StoredPublishedWiki page = stored.get();
        if (candidate.indexedContentHash() == null
                || !candidate.indexedContentHash().equals(page.contentHash())
                || candidate.revision() == null
                || candidate.revision() != page.revision()) {
            return RevalidationOutcome.rejected(AuthorityRejectionReason.STALE_REVISION);
        }
        try {
            return RevalidationOutcome.accepted(wikiAuthority(page,
                    publishedWikiContentReader.readSearchableContent(page)));
        } catch (PublishedWikiValidationException expectedDrift) {
            return RevalidationOutcome.rejected(AuthorityRejectionReason.INELIGIBLE);
        } catch (PublishedWikiUnavailableException infrastructureFailure) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.WIKI_AUTHORITY,
                    infrastructureFailure);
        }
    }

    private RevalidationOutcome revalidateSource(
            SearchCandidate candidate,
            long workspaceId,
            Map<Long, Optional<SourceSearchAuthorityDocument>> documents) {
        if (candidate.sourceChunkId() == null || candidate.documentId() == null
                || !candidate.stableId().equals(candidate.sourceChunkId().toString())) {
            return RevalidationOutcome.rejected(AuthorityRejectionReason.IDENTITY_MISMATCH);
        }
        Optional<SourceSearchAuthorityDocument> document;
        try {
            document = documents.computeIfAbsent(candidate.documentId(),
                    documentId -> sourceAuthorityRepository.findDocument(workspaceId, documentId));
        } catch (DataAccessException infrastructureFailure) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.SOURCE_AUTHORITY,
                    infrastructureFailure);
        }
        if (document.isEmpty()) {
            return RevalidationOutcome.rejected(AuthorityRejectionReason.AUTHORITY_MISSING);
        }
        if (!SourceSearchEligibilityPolicy.documentEligible(document.get())) {
            return RevalidationOutcome.rejected(AuthorityRejectionReason.INELIGIBLE);
        }
        var eligible = SourceSearchFreshness.eligibleDocuments(document.get());
        if (candidate.indexedContentHash() == null
                || candidate.sourceDocumentFingerprint() == null
                || candidate.sourceEligibleChunkCount() == null
                || candidate.sourceEligibleChunkCount() != eligible.size()
                || !candidate.sourceDocumentFingerprint()
                .equals(SourceSearchFreshness.fingerprint(document.get()))) {
            return RevalidationOutcome.rejected(AuthorityRejectionReason.STALE_REVISION);
        }
        var chunks = document.get().chunks().stream()
                .filter(chunk -> chunk.sourceChunkId() == candidate.sourceChunkId())
                .toList();
        if (chunks.isEmpty()) {
            return RevalidationOutcome.rejected(AuthorityRejectionReason.AUTHORITY_MISSING);
        }
        if (!SourceSearchEligibilityPolicy.chunkEligible(chunks.get(0))) {
            return RevalidationOutcome.rejected(AuthorityRejectionReason.INELIGIBLE);
        }
        if (!candidate.indexedContentHash().equals(chunks.get(0).contentHash())) {
            return RevalidationOutcome.rejected(AuthorityRejectionReason.STALE_REVISION);
        }
        return RevalidationOutcome.accepted(sourceAuthority(document.get(), chunks.get(0)));
    }

    private PublicationOutcome wikiPublicationCurrent(EvidenceItem item, long workspaceId) {
        Optional<StoredPublishedWiki> stored;
        try {
            stored = publishedWikiRepository.findPublishedByKnowledgeId(
                    workspaceId, item.stableId());
        } catch (DataAccessException infrastructureFailure) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.WIKI_AUTHORITY,
                    infrastructureFailure);
        }
        if (stored.isEmpty()) {
            return PublicationOutcome.rejected(AuthorityRejectionReason.AUTHORITY_MISSING);
        }
        if (item.revision() == null) {
            return PublicationOutcome.rejected(AuthorityRejectionReason.IDENTITY_MISMATCH);
        }
        StoredPublishedWiki page = stored.get();
        return page.contentHash().equals(item.contentHash())
                && page.revision() == item.revision()
                ? PublicationOutcome.CURRENT
                : PublicationOutcome.rejected(AuthorityRejectionReason.STALE_REVISION);
    }

    private PublicationOutcome sourcePublicationCurrent(EvidenceItem item, long workspaceId,
            Map<Long, Optional<SourceSearchAuthorityDocument>> sourceDocuments) {
        if (item.documentId() == null || item.sourceChunkId() == null) {
            return PublicationOutcome.rejected(AuthorityRejectionReason.IDENTITY_MISMATCH);
        }
        Optional<SourceSearchAuthorityDocument> document;
        try {
            document = sourceDocuments.computeIfAbsent(item.documentId(),
                    documentId -> sourceAuthorityRepository.findDocument(workspaceId, documentId));
        } catch (DataAccessException infrastructureFailure) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.SOURCE_AUTHORITY,
                    infrastructureFailure);
        }
        if (document.isEmpty()) {
            return PublicationOutcome.rejected(AuthorityRejectionReason.AUTHORITY_MISSING);
        }
        if (!SourceSearchEligibilityPolicy.documentEligible(document.get())) {
            return PublicationOutcome.rejected(AuthorityRejectionReason.INELIGIBLE);
        }
        var chunks = document.get().chunks().stream()
                .filter(chunk -> chunk.sourceChunkId() == item.sourceChunkId())
                .toList();
        if (chunks.isEmpty()) {
            return PublicationOutcome.rejected(AuthorityRejectionReason.AUTHORITY_MISSING);
        }
        if (!SourceSearchEligibilityPolicy.chunkEligible(chunks.get(0))) {
            return PublicationOutcome.rejected(AuthorityRejectionReason.INELIGIBLE);
        }
        return chunks.get(0).contentHash().equals(item.contentHash())
                ? PublicationOutcome.CURRENT
                : PublicationOutcome.rejected(AuthorityRejectionReason.STALE_REVISION);
    }

    private static AuthorityEvidence wikiAuthority(StoredPublishedWiki page, String content) {
        return new AuthorityEvidence(EvidenceKind.WIKI, page.knowledgeId(), content,
                page.contentHash(), page.knowledgeId(), page.title(), page.pageType().name(),
                page.markdownPath(), page.revision(), null, null, null, null,
                null, null, null);
    }

    private static AuthorityEvidence sourceAuthority(
            SourceSearchAuthorityDocument document,
            SourceSearchAuthorityChunk chunk) {
        return new AuthorityEvidence(EvidenceKind.SOURCE_CHUNK,
                Long.toString(chunk.sourceChunkId()), chunk.normalizedContent(),
                chunk.contentHash(), null, null, null, null, null,
                chunk.sourceChunkId(), document.documentId(), document.documentName(),
                chunk.chunkNo(), chunk.pageNo(), chunk.section(), chunk.headingPath());
    }

    static BoundedText bound(String content, int maxCharacters) {
        int count = content.codePointCount(0, content.length());
        if (count <= maxCharacters) {
            return new BoundedText(content, count, false);
        }
        int end = content.offsetByCodePoints(0, maxCharacters);
        return new BoundedText(content.substring(0, end), maxCharacters, true);
    }

    record BoundedText(String text, int characters, boolean truncated) {
    }

    /** Canonical authority fields re-validated at revalidation time; identity is application-owned. */
    record AuthorityEvidence(
            EvidenceKind kind,
            String stableId,
            String content,
            String contentHash,
            String knowledgeId,
            String title,
            String pageType,
            String path,
            Integer revision,
            Long sourceChunkId,
            Long documentId,
            String documentName,
            Integer chunkNo,
            Integer pageNo,
            String section,
            String headingPath
    ) {
        EvidenceItem toItem(EvidenceWorkspace workspace, double score, String snippet,
                            String boundedContent, boolean truncated) {
            return new EvidenceItem(kind, stableId, workspace, score, boundedContent, snippet,
                    truncated, contentHash, knowledgeId, title, pageType, path, revision,
                    sourceChunkId, documentId, documentName, chunkNo, pageNo, section, headingPath);
        }
    }
}
