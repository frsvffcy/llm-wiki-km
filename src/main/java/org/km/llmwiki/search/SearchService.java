package org.km.llmwiki.search;

import org.km.llmwiki.web.PageResponse;
import org.km.llmwiki.wiki.WikiPageType;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Shared application boundary used by REST now and Retrieval orchestration in a later Story. */
@Service
public class SearchService {

    static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int RRF_RANK_CONSTANT = 60;

    private static final Comparator<SearchResult> RESULT_ORDER =
            Comparator.comparingDouble(SearchResult::score).reversed()
                    .thenComparing(result -> result.kind().name())
                    .thenComparing(SearchResult::stableId);

    private final WorkspaceService workspaceService;
    private final FtsSearchIndexRepository repository;

    public SearchService(WorkspaceService workspaceService, FtsSearchIndexRepository repository) {
        this.workspaceService = workspaceService;
        this.repository = repository;
    }

    public PageResponse<List<SearchResult>> search(String query, String corpusValue,
                                                    String pageTypeValue, Long documentId,
                                                    Integer page, Integer size) {
        SearchCorpus corpus = SearchCorpus.from(corpusValue);
        String pageType = validatePageType(pageTypeValue);
        Long validatedDocumentId = validateDocumentId(documentId);
        validateCompatibleFilters(corpus, pageType, validatedDocumentId);
        int pageNumber = validatePage(page);
        int pageSize = validateSize(size);
        long offset = candidateOffset(pageNumber, pageSize);

        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
        if (query == null || query.isBlank()) {
            return PageResponse.of(List.of(), pageNumber, pageSize, 0);
        }

        String normalizedQuery = Normalizer.normalize(query.strip(), Normalizer.Form.NFC);
        // Validate once before any count query. Repository methods independently enforce the same boundary.
        FtsMatchQuery.literalExpression(normalizedQuery);

        long wikiTotal = includesWiki(corpus)
                ? repository.countWikiSearch(workspace.id(), normalizedQuery, pageType) : 0;
        long sourceTotal = includesSource(corpus)
                ? repository.countSourceSearch(workspace.id(), normalizedQuery, validatedDocumentId) : 0;
        long total = Math.addExact(wikiTotal, sourceTotal);
        if (total == 0 || offset >= total) {
            return PageResponse.of(List.of(), pageNumber, pageSize, total);
        }

        int candidateLimit = Math.toIntExact(Math.min(total, Math.addExact(offset, pageSize)));
        SearchWorkspaceProvenance provenance =
                new SearchWorkspaceProvenance(workspace.id(), workspace.name());
        List<SearchResult> fused = new ArrayList<>();
        if (includesWiki(corpus) && wikiTotal > 0) {
            addWikiResults(fused, provenance,
                    repository.searchWiki(workspace.id(), normalizedQuery, pageType,
                            (int) Math.min(wikiTotal, candidateLimit)));
        }
        if (includesSource(corpus) && sourceTotal > 0) {
            addSourceResults(fused, provenance,
                    repository.searchSource(workspace.id(), normalizedQuery, validatedDocumentId,
                            (int) Math.min(sourceTotal, candidateLimit)));
        }

        fused.sort(RESULT_ORDER);
        int fromIndex = Math.toIntExact(offset);
        int toIndex = Math.min(fused.size(), Math.addExact(fromIndex, pageSize));
        List<SearchResult> items = fromIndex >= fused.size()
                ? List.of() : List.copyOf(fused.subList(fromIndex, toIndex));
        return PageResponse.of(items, pageNumber, pageSize, total);
    }

    private static void addWikiResults(List<SearchResult> results,
                                       SearchWorkspaceProvenance workspace,
                                       List<WikiFtsSearchMatch> matches) {
        for (int index = 0; index < matches.size(); index++) {
            WikiFtsSearchMatch match = matches.get(index);
            results.add(new SearchResult(SearchResultKind.WIKI, match.knowledgeId(), score(index),
                    SearchSnippet.bounded(match.snippet()), workspace,
                    match.knowledgeId(), match.title(), match.pageType(), match.path(), match.revision(),
                    null, null, null, null, null, null, null));
        }
    }

    private static void addSourceResults(List<SearchResult> results,
                                         SearchWorkspaceProvenance workspace,
                                         List<SourceFtsSearchMatch> matches) {
        for (int index = 0; index < matches.size(); index++) {
            SourceFtsSearchMatch match = matches.get(index);
            String stableId = Long.toString(match.sourceChunkId());
            results.add(new SearchResult(SearchResultKind.SOURCE_CHUNK, stableId, score(index),
                    SearchSnippet.bounded(match.snippet()), workspace,
                    null, null, null, null, null,
                    match.sourceChunkId(), match.documentId(), match.documentName(), match.chunkNo(),
                    match.pageNo(), match.section(), match.headingPath()));
        }
    }

    private static double score(int zeroBasedRank) {
        return 1.0d / (RRF_RANK_CONSTANT + zeroBasedRank + 1);
    }

    private static boolean includesWiki(SearchCorpus corpus) {
        return corpus == SearchCorpus.WIKI || corpus == SearchCorpus.ALL;
    }

    private static boolean includesSource(SearchCorpus corpus) {
        return corpus == SearchCorpus.SOURCE || corpus == SearchCorpus.ALL;
    }

    private static String validatePageType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        try {
            return WikiPageType.valueOf(normalized).name();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown pageType filter: " + value);
        }
    }

    private static Long validateDocumentId(Long documentId) {
        if (documentId != null && documentId <= 0) {
            throw new IllegalArgumentException("documentId must be positive");
        }
        return documentId;
    }

    private static void validateCompatibleFilters(SearchCorpus corpus, String pageType,
                                                  Long documentId) {
        if (corpus == SearchCorpus.WIKI && documentId != null) {
            throw new IllegalArgumentException("documentId filter is only available for SOURCE or ALL");
        }
        if (corpus == SearchCorpus.SOURCE && pageType != null) {
            throw new IllegalArgumentException("pageType filter is only available for WIKI or ALL");
        }
    }

    private static int validatePage(Integer page) {
        if (page != null && page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        return page == null ? 0 : page;
    }

    private static int validateSize(Integer size) {
        int resolved = size == null ? DEFAULT_PAGE_SIZE : size;
        if (resolved < 1 || resolved > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return resolved;
    }

    private static long candidateOffset(int page, int size) {
        long offset = Math.multiplyExact((long) page, size);
        if (offset > Integer.MAX_VALUE - size) {
            throw new IllegalArgumentException("page is too large");
        }
        return offset;
    }
}
