package org.km.llmwiki.rag;

import org.jooq.exception.DataAccessException;
import org.km.llmwiki.search.SearchCandidate;
import org.km.llmwiki.search.SearchCandidatePage;
import org.km.llmwiki.search.SearchQuery;
import org.km.llmwiki.search.SearchService;
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
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Application-level Retrieval contract: FTS finds candidates, authority reads build evidence.
 *
 * <p>The service has no dependency on controllers, REST DTOs, prompts, or LLM providers.
 */
@Service
public class RetrievalService {

    private static final Comparator<SearchCandidate> CANDIDATE_ORDER =
            Comparator.comparingDouble(SearchCandidate::score).reversed()
                    .thenComparing(candidate -> candidate.kind().name())
                    .thenComparing(SearchCandidate::stableId);

    private final WorkspaceService workspaceService;
    private final SearchService searchService;
    private final PublishedWikiRepository publishedWikiRepository;
    private final PublishedWikiContentReader publishedWikiContentReader;
    private final SourceSearchAuthorityRepository sourceAuthorityRepository;

    public RetrievalService(WorkspaceService workspaceService,
                            SearchService searchService,
                            PublishedWikiRepository publishedWikiRepository,
                            PublishedWikiContentReader publishedWikiContentReader,
                            SourceSearchAuthorityRepository sourceAuthorityRepository) {
        this.workspaceService = workspaceService;
        this.searchService = searchService;
        this.publishedWikiRepository = publishedWikiRepository;
        this.publishedWikiContentReader = publishedWikiContentReader;
        this.sourceAuthorityRepository = sourceAuthorityRepository;
    }

    public EvidenceBundle retrieve(RetrievalRequest request) {
        RetrievalBudgetPolicy.ResolvedBudget limits = RetrievalBudgetPolicy.resolve(request);
        WorkspaceResponse active;
        try {
            active = workspaceService.findActiveWithoutValidation()
                    .orElseThrow(NoActiveWorkspaceException::new);
        } catch (DataAccessException infrastructureFailure) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.WORKSPACE_AUTHORITY,
                    infrastructureFailure);
        }
        SearchCandidatePage page;
        try {
            page = searchService.findCandidates(new SearchQuery(
                    request.query(), request.mode().searchCorpus(), null, null,
                    0, limits.candidateLimit()));
        } catch (DataAccessException infrastructureFailure) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.SEARCH_INDEX,
                    infrastructureFailure);
        }
        return assembleEvidence(request, active, page);
    }

    /** Testable boundary that also makes the Search-to-authority revalidation race explicit. */
    EvidenceBundle assembleEvidence(RetrievalRequest request, WorkspaceResponse active,
                                    SearchCandidatePage page) {
        RetrievalBudgetPolicy.ResolvedBudget limits = RetrievalBudgetPolicy.resolve(request);
        EvidenceWorkspace workspace = new EvidenceWorkspace(active.id(), active.name());
        List<SearchCandidate> ordered = page.items().stream().sorted(CANDIDATE_ORDER).toList();

        List<EvidenceItem> evidence = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        Map<Long, Optional<SourceSearchAuthorityDocument>> sourceDocuments = new HashMap<>();
        int usedCharacters = 0;
        int rejected = 0;
        boolean budgetTruncated = false;

        for (int index = 0; index < ordered.size(); index++) {
            SearchCandidate candidate = ordered.get(index);
            String identity = candidate.kind().name() + ":" + candidate.stableId();
            if (!identities.add(identity)) {
                continue;
            }
            if (evidence.size() >= limits.maxItems() || usedCharacters >= limits.maxCharacters()) {
                budgetTruncated = true;
                break;
            }

            Optional<AuthorityEvidence> authority = revalidate(
                    candidate, active.id(), sourceDocuments);
            if (authority.isEmpty()) {
                rejected++;
                continue;
            }

            int remaining = limits.maxCharacters() - usedCharacters;
            BoundedText bounded = bound(authority.get().content(), remaining);
            if (bounded.text().isBlank()) {
                budgetTruncated = true;
                break;
            }
            AuthorityEvidence trusted = authority.get();
            evidence.add(trusted.toItem(workspace, candidate.score(), candidate.snippet(),
                    bounded.text(), bounded.truncated()));
            usedCharacters += bounded.characters();
            budgetTruncated |= bounded.truncated();
            if (bounded.truncated() || evidence.size() >= limits.maxItems()) {
                budgetTruncated |= hasFurtherUniqueCandidate(ordered, index + 1, identities);
                break;
            }
        }

        EvidenceBudget budget = new EvidenceBudget(limits.maxItems(), limits.maxCharacters(),
                evidence.size(), usedCharacters, (usedCharacters + 3) / 4, budgetTruncated);
        return new EvidenceBundle(request.query().strip(), request.mode(), workspace, evidence,
                budget, ordered.size(), rejected, evidence.isEmpty());
    }

    private Optional<AuthorityEvidence> revalidate(
            SearchCandidate candidate,
            long workspaceId,
            Map<Long, Optional<SourceSearchAuthorityDocument>> sourceDocuments) {
        if (candidate.workspace() == null || candidate.workspace().id() != workspaceId) {
            return Optional.empty();
        }
        return switch (candidate.kind()) {
            case WIKI -> revalidateWiki(candidate, workspaceId);
            case SOURCE_CHUNK -> revalidateSource(candidate, workspaceId, sourceDocuments);
        };
    }

    private Optional<AuthorityEvidence> revalidateWiki(SearchCandidate candidate, long workspaceId) {
        if (candidate.knowledgeId() == null
                || !candidate.stableId().equals(candidate.knowledgeId())) {
            return Optional.empty();
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
            return Optional.empty();
        }
        StoredPublishedWiki page = stored.get();
        if (candidate.indexedContentHash() == null
                || !candidate.indexedContentHash().equals(page.contentHash())
                || candidate.revision() == null
                || candidate.revision() != page.revision()) {
            return Optional.empty();
        }
        try {
            return Optional.of(wikiAuthority(page,
                    publishedWikiContentReader.readSearchableContent(page)));
        } catch (PublishedWikiValidationException expectedDrift) {
            return Optional.empty();
        } catch (PublishedWikiUnavailableException infrastructureFailure) {
            throw new RetrievalUnavailableException(
                    RetrievalUnavailableException.Dependency.WIKI_AUTHORITY,
                    infrastructureFailure);
        }
    }

    private Optional<AuthorityEvidence> revalidateSource(
            SearchCandidate candidate,
            long workspaceId,
            Map<Long, Optional<SourceSearchAuthorityDocument>> documents) {
        if (candidate.sourceChunkId() == null || candidate.documentId() == null
                || !candidate.stableId().equals(candidate.sourceChunkId().toString())) {
            return Optional.empty();
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
        if (document.isEmpty()
                || !SourceSearchEligibilityPolicy.documentEligible(document.get())) {
            return Optional.empty();
        }
        var eligible = SourceSearchFreshness.eligibleDocuments(document.get());
        if (candidate.indexedContentHash() == null
                || candidate.sourceDocumentFingerprint() == null
                || candidate.sourceEligibleChunkCount() == null
                || candidate.sourceEligibleChunkCount() != eligible.size()
                || !candidate.sourceDocumentFingerprint()
                .equals(SourceSearchFreshness.fingerprint(document.get()))) {
            return Optional.empty();
        }
        return document.get().chunks().stream()
                .filter(chunk -> chunk.sourceChunkId() == candidate.sourceChunkId())
                .filter(SourceSearchEligibilityPolicy::chunkEligible)
                .filter(chunk -> candidate.indexedContentHash().equals(chunk.contentHash()))
                .findFirst()
                .map(chunk -> sourceAuthority(document.get(), chunk));
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

    private static BoundedText bound(String content, int maxCharacters) {
        int count = content.codePointCount(0, content.length());
        if (count <= maxCharacters) {
            return new BoundedText(content, count, false);
        }
        int end = content.offsetByCodePoints(0, maxCharacters);
        return new BoundedText(content.substring(0, end), maxCharacters, true);
    }

    private static boolean hasFurtherUniqueCandidate(List<SearchCandidate> candidates,
                                                     int fromIndex,
                                                     Set<String> seen) {
        for (int index = fromIndex; index < candidates.size(); index++) {
            SearchCandidate candidate = candidates.get(index);
            if (!seen.contains(candidate.kind().name() + ":" + candidate.stableId())) {
                return true;
            }
        }
        return false;
    }

    private record BoundedText(String text, int characters, boolean truncated) {
    }

    private record AuthorityEvidence(
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
