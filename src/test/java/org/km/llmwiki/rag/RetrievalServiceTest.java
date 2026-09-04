package org.km.llmwiki.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
import org.km.llmwiki.search.SourceSearchFreshness;
import org.km.llmwiki.search.vector.VectorCandidateSearchQuery;
import org.km.llmwiki.search.vector.VectorCandidateSearchService;
import org.km.llmwiki.search.vector.VectorCandidateSearchUnavailableException;
import org.km.llmwiki.wiki.PageStatus;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.PublishedWikiUnavailableException;
import org.km.llmwiki.wiki.PublishedWikiValidationException;
import org.km.llmwiki.wiki.StoredPublishedWiki;
import org.km.llmwiki.wiki.WikiPageType;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class RetrievalServiceTest {

    private static final long WORKSPACE_ID = 7L;

    private WorkspaceService workspaceService;
    private SearchService searchService;
    private PublishedWikiRepository wikiRepository;
    private PublishedWikiContentReader wikiContentReader;
    private SourceSearchAuthorityRepository sourceRepository;
    private RetrievalService retrievalService;
    private Map<String, String> wikiHashes;
    private Map<Long, SourceSearchAuthorityDocument> sourceDocuments;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        searchService = mock(SearchService.class);
        wikiRepository = mock(PublishedWikiRepository.class);
        wikiContentReader = mock(PublishedWikiContentReader.class);
        sourceRepository = mock(SourceSearchAuthorityRepository.class);
        wikiHashes = new HashMap<>();
        sourceDocuments = new HashMap<>();
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
                .thenThrow(new PublishedWikiValidationException("content_hash drift"));
        SearchCandidate foreign = new SearchCandidate(SearchResultKind.WIKI, "foreign", 0.8,
                "snippet", new SearchWorkspaceProvenance(99, "Foreign"), "foreign", "Foreign",
                "CONCEPT", "vault/concepts/foreign.md", 1,
                "0".repeat(64), null, null, null, null, null, null, null, null, null);
        SourceSearchAuthorityChunk invalidChunk = new SourceSearchAuthorityChunk(
                30L, 1, null, null, null, "stale", "0".repeat(64));
        SourceSearchAuthorityDocument invalidDocument = new SourceSearchAuthorityDocument(
                WORKSPACE_ID, 300L, "stale.txt", "PENDING", "PROCESSED",
                List.of(invalidChunk));
        sourceDocuments.put(300L, invalidDocument);
        when(sourceRepository.findDocument(WORKSPACE_ID, 300L)).thenReturn(Optional.of(
                invalidDocument));
        when(searchService.findCandidates(any())).thenReturn(page(List.of(
                wikiCandidate("drift", 0.9), foreign, sourceCandidate(30L, 300L, 0.7))));

        EvidenceBundle bundle = retrievalService.retrieve(
                RetrievalRequest.defaults("missing", RetrievalMode.HYBRID_FTS));

        assertThat(bundle.items()).isEmpty();
        assertThat(bundle.insufficientEvidence()).isTrue();
        assertThat(bundle.rejectedCandidateCount()).isEqualTo(3);
        verify(wikiRepository, never()).findPublishedByKnowledgeId(WORKSPACE_ID, "foreign");
    }

    @Test
    void returnsValidEvidenceWhenAnotherCandidateHasCanonicalWikiDrift() {
        stubWiki("stale", "stale authority");
        stubWiki("valid", "trusted authority");
        when(wikiContentReader.readSearchableContent(
                wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "stale").orElseThrow()))
                .thenThrow(new PublishedWikiValidationException("frontmatter drift"));
        when(searchService.findCandidates(any())).thenReturn(page(List.of(
                wikiCandidate("stale", 0.9), wikiCandidate("valid", 0.8))));

        EvidenceBundle bundle = retrievalService.retrieve(
                RetrievalRequest.defaults("mixed", RetrievalMode.WIKI_ONLY));

        assertThat(bundle.items()).extracting(EvidenceItem::stableId).containsExactly("valid");
        assertThat(bundle.rejectedCandidateCount()).isOne();
        assertThat(bundle.insufficientEvidence()).isFalse();
    }

    @Test
    void propagatesDatabaseAuthorityFailureInsteadOfReturningInsufficientEvidence() {
        stubSource(50L, 500L, "trusted source");
        when(sourceRepository.findDocument(WORKSPACE_ID, 500L))
                .thenThrow(new org.jooq.exception.DataAccessException("database unavailable"));
        when(searchService.findCandidates(any())).thenReturn(page(List.of(
                sourceCandidate(50L, 500L, 0.9))));

        assertThatThrownBy(() -> retrievalService.retrieve(
                RetrievalRequest.defaults("database", RetrievalMode.SOURCE_ONLY)))
                .isInstanceOf(RetrievalUnavailableException.class)
                .satisfies(failure -> assertThat(((RetrievalUnavailableException) failure)
                        .dependency()).isEqualTo(
                        RetrievalUnavailableException.Dependency.SOURCE_AUTHORITY));
    }

    @Test
    void propagatesFilesystemAuthorityFailureInsteadOfReturningInsufficientEvidence() {
        stubWiki("unreadable", "trusted wiki");
        when(wikiContentReader.readSearchableContent(any()))
                .thenThrow(new PublishedWikiUnavailableException(
                        "Published Wiki Markdown could not be read",
                        new IOException("permission denied")));
        when(searchService.findCandidates(any())).thenReturn(page(List.of(
                wikiCandidate("unreadable", 0.9))));

        assertThatThrownBy(() -> retrievalService.retrieve(
                RetrievalRequest.defaults("filesystem", RetrievalMode.WIKI_ONLY)))
                .isInstanceOf(RetrievalUnavailableException.class)
                .satisfies(failure -> assertThat(((RetrievalUnavailableException) failure)
                        .dependency()).isEqualTo(
                        RetrievalUnavailableException.Dependency.WIKI_AUTHORITY));
    }

    @Test
    void propagatesSearchDatabaseFailureAsUnavailable() {
        when(searchService.findCandidates(any()))
                .thenThrow(new org.jooq.exception.DataAccessException("search database unavailable"));

        assertThatThrownBy(() -> retrievalService.retrieve(
                RetrievalRequest.defaults("search", RetrievalMode.HYBRID_FTS)))
                .isInstanceOf(RetrievalUnavailableException.class)
                .satisfies(failure -> assertThat(((RetrievalUnavailableException) failure)
                        .dependency()).isEqualTo(
                        RetrievalUnavailableException.Dependency.SEARCH_INDEX));
    }

    @Test
    void propagatesWorkspaceDatabaseFailureAsUnavailable() {
        when(workspaceService.findActiveWithoutValidation())
                .thenThrow(new org.jooq.exception.DataAccessException("workspace unavailable"));

        assertThatThrownBy(() -> retrievalService.retrieve(
                RetrievalRequest.defaults("workspace", RetrievalMode.HYBRID_FTS)))
                .isInstanceOf(RetrievalUnavailableException.class)
                .satisfies(failure -> assertThat(((RetrievalUnavailableException) failure)
                        .dependency()).isEqualTo(
                        RetrievalUnavailableException.Dependency.WORKSPACE_AUTHORITY));
    }

    @Test
    void rejectsSearchCandidatesWhenWikiOrSourceAuthorityDriftsBeforeRevalidation() {
        stubWiki("race-wiki", "wiki v1");
        stubSource(40L, 400L, "source v1");
        SearchCandidate wiki = wikiCandidate("race-wiki", 0.9);
        SearchCandidate source = sourceCandidate(40L, 400L, 0.8);

        StoredPublishedWiki changedWiki = new StoredPublishedWiki(1, WORKSPACE_ID, "race-wiki",
                "Title race-wiki", "title race-wiki", WikiPageType.CONCEPT,
                "vault/concepts/title-race-wiki.md", PageStatus.PUBLISHED, sha256("wiki v2"), 2,
                "2026-08-31T00:00:00Z", "2026-08-31T00:01:00Z");
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "race-wiki"))
                .thenReturn(Optional.of(changedWiki));
        SourceSearchAuthorityChunk changedChunk = new SourceSearchAuthorityChunk(40L, 1, 3,
                "Section", "Root > Section", "source v2", sha256("source v2"));
        when(sourceRepository.findDocument(WORKSPACE_ID, 400L)).thenReturn(Optional.of(
                new SourceSearchAuthorityDocument(WORKSPACE_ID, 400L, "source.txt", "PENDING",
                        "PROCESSED", List.of(changedChunk))));
        when(searchService.findCandidates(any())).thenReturn(page(List.of(wiki, source)));

        EvidenceBundle bundle = retrievalService.retrieve(
                RetrievalRequest.defaults("race", RetrievalMode.HYBRID_FTS));

        assertThat(bundle.items()).isEmpty();
        assertThat(bundle.rejectedCandidateCount()).isEqualTo(2);
        assertThat(bundle.insufficientEvidence()).isTrue();
        verify(wikiContentReader, never()).readSearchableContent(changedWiki);
    }

    @Test
    void hybridFusesLexicalAndVectorCandidatesAndKeepsDiagnostics() {
        stubWiki("lexical", "lexical authority");
        stubWiki("semantic", "semantic authority");
        VectorCandidateSearchService vector = mock(VectorCandidateSearchService.class);
        when(searchService.findCandidates(any())).thenReturn(page(List.of(
                wikiCandidate("lexical", 10.0))));
        when(vector.findCandidates(any(VectorCandidateSearchQuery.class), any()))
                .thenReturn(page(List.of(wikiCandidate("semantic", 0.1))));
        retrievalService = new RetrievalService(workspaceService, searchService, wikiRepository,
                wikiContentReader, sourceRepository, vector);

        EvidenceBundle bundle = retrievalService.retrieve(RetrievalRequest.of(
                "hybrid", RetrievalMode.WIKI_ONLY, RetrievalStrategy.HYBRID, 8, 100));

        assertThat(bundle.items()).extracting(EvidenceItem::stableId)
                .containsExactly("lexical", "semantic");
        assertThat(bundle.diagnostics().strategy()).isEqualTo(RetrievalStrategy.HYBRID);
        assertThat(bundle.diagnostics().lexicalSignalUsed()).isTrue();
        assertThat(bundle.diagnostics().vectorSignalUsed()).isTrue();
        assertThat(bundle.diagnostics().degradedFallback()).isFalse();
    }

    @Test
    void hybridMarksVectorUnavailableAsExplicitDegradedLexicalFallback() {
        stubWiki("lexical", "lexical authority");
        VectorCandidateSearchService vector = mock(VectorCandidateSearchService.class);
        when(searchService.findCandidates(any())).thenReturn(page(List.of(
                wikiCandidate("lexical", 1.0))));
        when(vector.findCandidates(any(VectorCandidateSearchQuery.class), any()))
                .thenThrow(new VectorCandidateSearchUnavailableException(
                        VectorCandidateSearchUnavailableException.Dependency.VECTOR_REPOSITORY,
                        new IllegalStateException("extension unavailable")));
        retrievalService = new RetrievalService(workspaceService, searchService, wikiRepository,
                wikiContentReader, sourceRepository, vector);

        EvidenceBundle bundle = retrievalService.retrieve(RetrievalRequest.of(
                "fallback", RetrievalMode.WIKI_ONLY, RetrievalStrategy.HYBRID, 8, 100));

        assertThat(bundle.items()).extracting(EvidenceItem::stableId).containsExactly("lexical");
        assertThat(bundle.diagnostics().degradedFallback()).isTrue();
        assertThat(bundle.diagnostics().vectorUnavailable()).isTrue();
        assertThat(bundle.diagnostics().vectorSignalUsed()).isFalse();
        assertThat(bundle.diagnostics().vectorUnavailableReason()).contains("Vector candidate");
    }

    @Test
    void hybridTreatsProjectionReadinessInvalidationAsDegradedLexicalFallback() {
        stubWiki("lexical", "lexical authority");
        VectorCandidateSearchService vector = mock(VectorCandidateSearchService.class);
        when(searchService.findCandidates(any())).thenReturn(page(List.of(
                wikiCandidate("lexical", 1.0))));
        when(vector.findCandidates(any(VectorCandidateSearchQuery.class), any()))
                .thenThrow(new VectorCandidateSearchUnavailableException(
                        VectorCandidateSearchUnavailableException.Dependency.PROJECTION_READINESS,
                        new IllegalStateException("readiness generation changed")));
        retrievalService = new RetrievalService(workspaceService, searchService, wikiRepository,
                wikiContentReader, sourceRepository, vector);

        EvidenceBundle bundle = retrievalService.retrieve(RetrievalRequest.of(
                "fallback", RetrievalMode.WIKI_ONLY, RetrievalStrategy.HYBRID, 8, 100));

        assertThat(bundle.items()).extracting(EvidenceItem::stableId).containsExactly("lexical");
        assertThat(bundle.diagnostics().degradedFallback()).isTrue();
        assertThat(bundle.diagnostics().vectorUnavailable()).isTrue();
        assertThat(bundle.diagnostics().vectorSignalUsed()).isFalse();
    }

    @Test
    void hybridPropagatesUnexpectedVectorRuntimeDefect() {
        stubWiki("lexical", "lexical authority");
        IllegalStateException defect = new IllegalStateException("programming defect");
        VectorCandidateSearchService vector = mock(VectorCandidateSearchService.class);
        when(searchService.findCandidates(any())).thenReturn(page(List.of(
                wikiCandidate("lexical", 1.0))));
        when(vector.findCandidates(any(VectorCandidateSearchQuery.class), any()))
                .thenThrow(defect);
        retrievalService = new RetrievalService(workspaceService, searchService, wikiRepository,
                wikiContentReader, sourceRepository, vector);

        assertThatThrownBy(() -> retrievalService.retrieve(RetrievalRequest.of(
                "defect", RetrievalMode.WIKI_ONLY, RetrievalStrategy.HYBRID, 8, 100)))
                .isSameAs(defect);
    }

    @Test
    void semanticStrategyFailsClosedWhenVectorIsUnavailable() {
        VectorCandidateSearchService vector = mock(VectorCandidateSearchService.class);
        when(vector.findCandidates(any(VectorCandidateSearchQuery.class), any()))
                .thenThrow(new VectorCandidateSearchUnavailableException(
                        VectorCandidateSearchUnavailableException.Dependency.VECTOR_CAPABILITY,
                        new IllegalStateException("disabled")));
        retrievalService = new RetrievalService(workspaceService, searchService, wikiRepository,
                wikiContentReader, sourceRepository, vector);

        assertThatThrownBy(() -> retrievalService.retrieve(RetrievalRequest.of(
                "semantic", RetrievalMode.WIKI_ONLY, RetrievalStrategy.SEMANTIC, 8, 100)))
                .isInstanceOf(RetrievalUnavailableException.class)
                .satisfies(failure -> assertThat(((RetrievalUnavailableException) failure)
                        .dependency()).isEqualTo(RetrievalUnavailableException.Dependency.VECTOR_SEARCH));
        verify(searchService, never()).findCandidates(any());
    }

    @Test
    void revalidatesAuthorityAfterHybridFusionAndRejectsDriftedCandidate() {
        stubWiki("drifted", "old content");
        SearchCandidate lexical = wikiCandidate("drifted", 10.0);
        SearchCandidate vectorCandidate = wikiCandidate("drifted", 0.9);
        when(searchService.findCandidates(any())).thenReturn(page(List.of(lexical)));
        VectorCandidateSearchService vector = mock(VectorCandidateSearchService.class);
        when(vector.findCandidates(any(VectorCandidateSearchQuery.class), any()))
                .thenReturn(page(List.of(vectorCandidate)));
        StoredPublishedWiki changed = new StoredPublishedWiki(99, WORKSPACE_ID, "drifted",
                "Title drifted", "title drifted", WikiPageType.CONCEPT,
                "vault/concepts/drifted.md", PageStatus.PUBLISHED, sha256("new content"), 2,
                "2026-08-31T00:00:00Z", "2026-08-31T00:01:00Z");
        when(wikiRepository.findPublishedByKnowledgeId(WORKSPACE_ID, "drifted"))
                .thenReturn(Optional.of(changed));
        retrievalService = new RetrievalService(workspaceService, searchService, wikiRepository,
                wikiContentReader, sourceRepository, vector);

        EvidenceBundle bundle = retrievalService.retrieve(RetrievalRequest.of(
                "race", RetrievalMode.WIKI_ONLY, RetrievalStrategy.HYBRID, 8, 100));

        assertThat(bundle.items()).isEmpty();
        assertThat(bundle.rejectedCandidateCount()).isOne();
        assertThat(bundle.insufficientEvidence()).isTrue();
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
        wikiHashes.put(knowledgeId, page.contentHash());
    }

    private void stubSource(long chunkId, long documentId, String content) {
        SourceSearchAuthorityChunk chunk = new SourceSearchAuthorityChunk(chunkId, 1, 3,
                "Section", "Root > Section", content, sha256(content));
        SourceSearchAuthorityDocument document = new SourceSearchAuthorityDocument(WORKSPACE_ID,
                documentId, "source.txt", "PENDING", "PROCESSED", List.of(chunk));
        sourceDocuments.put(documentId, document);
        when(sourceRepository.findDocument(WORKSPACE_ID, documentId)).thenReturn(Optional.of(document));
    }

    private SearchCandidate wikiCandidate(String knowledgeId, double score) {
        return new SearchCandidate(SearchResultKind.WIKI, knowledgeId, score, "wiki snippet",
                provenance(), knowledgeId, "Title " + knowledgeId, "CONCEPT",
                "vault/concepts/title-" + knowledgeId + ".md", 1,
                wikiHashes.get(knowledgeId), null, null,
                null, null, null, null, null, null, null);
    }

    private SearchCandidate sourceCandidate(long chunkId, long documentId, double score) {
        SourceSearchAuthorityDocument authority = sourceDocuments.get(documentId);
        SourceSearchAuthorityChunk chunk = authority.chunks().stream()
                .filter(item -> item.sourceChunkId() == chunkId).findFirst().orElseThrow();
        return new SearchCandidate(SearchResultKind.SOURCE_CHUNK, Long.toString(chunkId), score,
                "source snippet", provenance(), null, null, null, null, null,
                chunk.contentHash(), SourceSearchFreshness.fingerprint(authority),
                SourceSearchFreshness.eligibleDocuments(authority).size(),
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
