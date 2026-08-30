package org.km.llmwiki.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.search.SearchCandidate;
import org.km.llmwiki.search.SearchCandidatePage;
import org.km.llmwiki.search.SearchCorpus;
import org.km.llmwiki.search.SearchQuery;
import org.km.llmwiki.search.SearchResult;
import org.km.llmwiki.search.SearchResultKind;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.search.SearchWorkspaceProvenance;
import org.km.llmwiki.search.SourceSearchAuthorityChunk;
import org.km.llmwiki.search.SourceSearchAuthorityDocument;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.wiki.PageStatus;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.StoredPublishedWiki;
import org.km.llmwiki.wiki.WikiPageType;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    private static final long WORKSPACE_ID = 7L;

    private WorkspaceService workspaceService;
    private SearchService searchService;
    private PublishedWikiRepository wikiRepository;
    private PublishedWikiContentReader wikiContentReader;
    private SourceSearchAuthorityRepository sourceRepository;
    private RetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        searchService = mock(SearchService.class);
        wikiRepository = mock(PublishedWikiRepository.class);
        wikiContentReader = mock(PublishedWikiContentReader.class);
        sourceRepository = mock(SourceSearchAuthorityRepository.class);
        retrievalService = new RetrievalService(workspaceService, searchService, wikiRepository,
                wikiContentReader, sourceRepository);

        when(workspaceService.findActiveWithoutValidation()).thenReturn(Optional.of(workspace()));
        when(searchService.findCandidates(any())).thenReturn(page(List.of()));
    }

    @Test
    void mapsEveryModeToSharedSearchApplicationContractWithoutRestDependency() {
        retrievalService.retrieve(RetrievalRequest.defaults("wiki", RetrievalMode.WIKI_ONLY));
        retrievalService.retrieve(RetrievalRequest.defaults("source", RetrievalMode.SOURCE_ONLY));
        retrievalService.retrieve(RetrievalRequest.defaults("hybrid", RetrievalMode.HYBRID_FTS));

        ArgumentCaptor<SearchQuery> queries = ArgumentCaptor.forClass(SearchQuery.class);
        verify(searchService, org.mockito.Mockito.times(3)).findCandidates(queries.capture());
        assertThat(queries.getAllValues()).extracting(SearchQuery::corpus)
                .containsExactly(SearchCorpus.WIKI, SearchCorpus.SOURCE, SearchCorpus.ALL);
        assertThat(queries.getAllValues()).allSatisfy(query -> {
            assertThat(query.page()).isZero();
            assertThat(query.size()).isEqualTo(32);
        });

        assertThat(List.of(RetrievalService.class.getDeclaredFields()))
                .extracting(Field::getType)
                .doesNotContain(SearchResult.class)
                .allSatisfy(type -> {
                    assertThat(type.getSimpleName()).doesNotContain("Controller");
                    assertThat(type.getPackageName()).doesNotStartWith("org.km.llmwiki.web");
                });
    }

    @Test
    void deterministicallyOrdersAndDeduplicatesBeforeBudgetAccounting() {
        stubWiki("a", "AAAA");
        stubWiki("b", "BBBBB");
        stubSource(20L, 200L, "source");
        when(searchService.findCandidates(any())).thenReturn(page(List.of(
                wikiCandidate("b", 0.5),
                wikiCandidate("a", 0.5),
                sourceCandidate(20L, 200L, 0.5),
                wikiCandidate("a", 0.5))));

        EvidenceBundle bundle = retrievalService.retrieve(
                new RetrievalRequest("ordering", RetrievalMode.HYBRID_FTS, 8, 100));

        assertThat(bundle.items()).extracting(EvidenceItem::stableIdentity)
                .containsExactly("SOURCE_CHUNK:20", "WIKI:a", "WIKI:b");
        assertThat(bundle.budget().usedItems()).isEqualTo(3);
        assertThat(bundle.budget().usedCharacters()).isEqualTo(15);
        assertThat(bundle.budget().estimatedTokens()).isEqualTo(4);
        assertThat(bundle.budget().truncated()).isFalse();
        assertThat(bundle.searchedCandidateCount()).isEqualTo(4);
        assertThat(bundle.rejectedCandidateCount()).isZero();
    }

    @Test
    void appliesItemAndUnicodeCharacterBudgetsAtCodePointBoundaries() {
        stubWiki("a", "A😀BC");
        stubWiki("b", "second");
        when(searchService.findCandidates(any())).thenReturn(page(List.of(
                wikiCandidate("a", 0.9), wikiCandidate("b", 0.8))));

        EvidenceBundle characterBound = retrievalService.retrieve(
                new RetrievalRequest("budget", RetrievalMode.WIKI_ONLY, 8, 2));
        assertThat(characterBound.items()).singleElement().satisfies(item -> {
            assertThat(item.content()).isEqualTo("A😀");
            assertThat(item.contentTruncated()).isTrue();
        });
        assertThat(characterBound.budget().usedCharacters()).isEqualTo(2);
        assertThat(characterBound.budget().truncated()).isTrue();

        EvidenceBundle itemBound = retrievalService.retrieve(
                new RetrievalRequest("budget", RetrievalMode.WIKI_ONLY, 1, 100));
        assertThat(itemBound.items()).extracting(EvidenceItem::stableId).containsExactly("a");
        assertThat(itemBound.budget().usedItems()).isOne();
        assertThat(itemBound.budget().truncated()).isTrue();
    }

    @Test
    void returnsBusinessInsufficientSignalWhenAuthorityOrWorkspaceRevalidationFails() {
        stubWiki("drift", "trusted");
        when(wikiContentReader.readSearchableContent(any()))
                .thenThrow(new IllegalStateException("content_hash drift"));
        SearchCandidate foreign = new SearchCandidate(SearchResultKind.WIKI, "foreign", 0.8,
                "snippet", new SearchWorkspaceProvenance(99, "Foreign"), "foreign", "Foreign",
                "CONCEPT", "vault/concepts/foreign.md", 1,
                null, null, null, null, null, null, null);
        SourceSearchAuthorityChunk invalidChunk = new SourceSearchAuthorityChunk(
                30L, 1, null, null, null, "stale", "0".repeat(64));
        when(sourceRepository.findDocument(WORKSPACE_ID, 300L)).thenReturn(Optional.of(
                new SourceSearchAuthorityDocument(WORKSPACE_ID, 300L, "stale.txt", "PENDING",
                        "PROCESSED", List.of(invalidChunk))));
        when(searchService.findCandidates(any())).thenReturn(page(List.of(
                wikiCandidate("drift", 0.9), foreign, sourceCandidate(30L, 300L, 0.7))));

        EvidenceBundle bundle = retrievalService.retrieve(
                RetrievalRequest.defaults("missing", RetrievalMode.HYBRID_FTS));

        assertThat(bundle.items()).isEmpty();
        assertThat(bundle.insufficientEvidence()).isTrue();
        assertThat(bundle.rejectedCandidateCount()).isEqualTo(3);
        verify(wikiRepository, never()).findPublishedByKnowledgeId(WORKSPACE_ID, "foreign");
    }

    private void stubWiki(String knowledgeId, String content) {
        StoredPublishedWiki page = new StoredPublishedWiki(knowledgeId.hashCode() & 0x7fffffff,
                WORKSPACE_ID, knowledgeId, "Title " + knowledgeId, "title " + knowledgeId,
                WikiPageType.CONCEPT, "vault/concepts/title-" + knowledgeId + ".md",
                PageStatus.PUBLISHED, sha256(content), 1,
                "2026-08-31T00:00:00Z", "2026-08-31T00:00:00Z");
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, knowledgeId))
                .thenReturn(Optional.of(page));
        when(wikiContentReader.readSearchableContent(page)).thenReturn(content);
    }

    private void stubSource(long chunkId, long documentId, String content) {
        SourceSearchAuthorityChunk chunk = new SourceSearchAuthorityChunk(chunkId, 1, 3,
                "Section", "Root > Section", content, sha256(content));
        when(sourceRepository.findDocument(WORKSPACE_ID, documentId)).thenReturn(Optional.of(
                new SourceSearchAuthorityDocument(WORKSPACE_ID, documentId, "source.txt", "PENDING",
                        "PROCESSED", List.of(chunk))));
    }

    private static SearchCandidate wikiCandidate(String knowledgeId, double score) {
        return new SearchCandidate(SearchResultKind.WIKI, knowledgeId, score, "wiki snippet",
                provenance(), knowledgeId, "Title " + knowledgeId, "CONCEPT",
                "vault/concepts/title-" + knowledgeId + ".md", 1,
                null, null, null, null, null, null, null);
    }

    private static SearchCandidate sourceCandidate(long chunkId, long documentId, double score) {
        return new SearchCandidate(SearchResultKind.SOURCE_CHUNK, Long.toString(chunkId), score,
                "source snippet", provenance(), null, null, null, null, null,
                chunkId, documentId, "source.txt", 1, 3, "Section", "Root > Section");
    }

    private static SearchCandidatePage page(List<SearchCandidate> candidates) {
        return new SearchCandidatePage(candidates, 0, Math.max(1, candidates.size()), candidates.size());
    }

    private static SearchWorkspaceProvenance provenance() {
        return new SearchWorkspaceProvenance(WORKSPACE_ID, "Retrieval workspace");
    }

    private static WorkspaceResponse workspace() {
        return new WorkspaceResponse(WORKSPACE_ID, "Retrieval workspace", "/tmp/retrieval",
                "/tmp/retrieval/inbox", "/tmp/retrieval/archive", "/tmp/retrieval/vault",
                "/tmp/retrieval/data", "/tmp/retrieval/config", "ACTIVE",
                "2026-08-31T00:00:00Z", "2026-08-31T00:00:00Z");
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
