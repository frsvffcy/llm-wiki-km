package org.km.llmwiki.search;

import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.StoredPublishedWiki;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Recomputes drift from authority and fails closed on canonical Wiki filesystem uncertainty. */
@Service
public class SearchHealthService {

    private final WorkspaceService workspaceService;
    private final PublishedWikiRepository publishedWikiRepository;
    private final PublishedWikiContentReader publishedWikiContentReader;
    private final SourceSearchAuthorityRepository sourceAuthorityRepository;
    private final FtsSearchIndexRepository ftsRepository;
    private final WikiSearchIndexSyncRepository wikiSyncRepository;
    private final SourceSearchIndexSyncRepository sourceSyncRepository;
    private final FtsRebuildStateRepository rebuildStateRepository;

    public SearchHealthService(WorkspaceService workspaceService,
                               PublishedWikiRepository publishedWikiRepository,
                               PublishedWikiContentReader publishedWikiContentReader,
                               SourceSearchAuthorityRepository sourceAuthorityRepository,
                               FtsSearchIndexRepository ftsRepository,
                               WikiSearchIndexSyncRepository wikiSyncRepository,
                               SourceSearchIndexSyncRepository sourceSyncRepository,
                               FtsRebuildStateRepository rebuildStateRepository) {
        this.workspaceService = workspaceService;
        this.publishedWikiRepository = publishedWikiRepository;
        this.publishedWikiContentReader = publishedWikiContentReader;
        this.sourceAuthorityRepository = sourceAuthorityRepository;
        this.ftsRepository = ftsRepository;
        this.wikiSyncRepository = wikiSyncRepository;
        this.sourceSyncRepository = sourceSyncRepository;
        this.rebuildStateRepository = rebuildStateRepository;
    }

    public SearchHealthResult check(String corpusValue) {
        SearchCorpus requested = SearchCorpus.from(corpusValue);
        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
        Map<SearchCorpus, FtsRebuildState> states = rebuildStateRepository.findAll(workspace.id())
                .stream().collect(Collectors.toMap(FtsRebuildState::corpus, Function.identity()));
        List<SearchCorpusHealth> corpora = new ArrayList<>();
        for (SearchCorpus corpus : FtsRebuildService.physicalCorpora(requested)) {
            corpora.add(corpus == SearchCorpus.WIKI
                    ? checkWiki(workspace.id(), states.get(corpus))
                    : checkSource(workspace.id(), states.get(corpus)));
        }
        SearchHealthSummary summary = new SearchHealthSummary(
                indexed(corpora, SearchCorpus.WIKI), indexed(corpora, SearchCorpus.SOURCE),
                corpora.stream().mapToLong(SearchCorpusHealth::missing).sum(),
                corpora.stream().mapToLong(SearchCorpusHealth::stale).sum(),
                corpora.stream().mapToLong(SearchCorpusHealth::orphan).sum(),
                corpora.stream().mapToLong(SearchCorpusHealth::failed).sum());
        SearchHealthStatus status = combinedStatus(corpora);
        return new SearchHealthResult(workspace.id(), workspace.name(), requested, status, summary,
                corpora, DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
    }

    private SearchCorpusHealth checkWiki(long workspaceId, FtsRebuildState rebuildState) {
        List<StoredPublishedWiki> pages = publishedWikiRepository.findAllPublished(workspaceId);
        Map<String, StoredPublishedWiki> authority = pages.stream()
                .collect(Collectors.toMap(StoredPublishedWiki::knowledgeId, Function.identity()));
        Map<String, String> canonicalContent = new HashMap<>();
        long failed = rebuildFailure(rebuildState);
        for (StoredPublishedWiki page : pages) {
            try {
                canonicalContent.put(page.knowledgeId(),
                        publishedWikiContentReader.readSearchableContent(page));
            } catch (RuntimeException drift) {
                failed++;
            }
        }

        List<WikiIndexProjection> rows = ftsRepository.listKnowledgeProjections(workspaceId);
        Map<String, List<WikiIndexProjection>> valid = rows.stream()
                .filter(WikiIndexProjection::identityValid)
                .collect(Collectors.groupingBy(WikiIndexProjection::knowledgeId));
        long missing = authority.keySet().stream()
                .filter(id -> valid.getOrDefault(id, List.of()).size() != 1).count();
        long orphan = rows.stream().filter(row -> !row.identityValid()
                || !authority.containsKey(row.knowledgeId())).count()
                + ftsRepository.countDanglingIdentities(workspaceId, "KNOWLEDGE");

        Map<Long, StoredWikiSearchIndexSync> ledgers = wikiSyncRepository.findAll(workspaceId)
                .stream().collect(Collectors.toMap(StoredWikiSearchIndexSync::knowledgePageId,
                        Function.identity()));
        Set<String> stale = new HashSet<>();
        for (StoredPublishedWiki page : pages) {
            List<WikiIndexProjection> matches = valid.getOrDefault(page.knowledgeId(), List.of());
            if (matches.size() == 1 && canonicalContent.containsKey(page.knowledgeId())) {
                WikiIndexProjection row = matches.getFirst();
                if (!Objects.equals(row.title(), page.title())
                        || !Objects.equals(row.normalizedTitle(), page.normalizedTitle())
                        || !Objects.equals(row.content(), canonicalContent.get(page.knowledgeId()))
                        || !Objects.equals(row.markdownPath(), page.markdownPath())
                        || !Objects.equals(row.pageType(), page.pageType().name())
                        || !Objects.equals(row.pageStatus(), page.status().name())
                        || !Objects.equals(row.contentHash(), page.contentHash())) {
                    stale.add(page.knowledgeId());
                }
            }
            StoredWikiSearchIndexSync ledger = ledgers.get(page.id());
            if (ledger == null || ledger.status() != WikiSearchIndexSyncStatus.SYNCED
                    || !Objects.equals(ledger.contentHash(), page.contentHash())
                    || !Objects.equals(ledger.indexedContentHash(), page.contentHash())
                    || !Objects.equals(ledger.indexedRevision(), page.revision())) {
                stale.add(page.knowledgeId());
            }
        }
        long indexed = valid.entrySet().stream()
                .filter(entry -> authority.containsKey(entry.getKey()) && entry.getValue().size() == 1)
                .count();
        return corpusHealth(SearchCorpus.WIKI, indexed, missing, stale.size(), orphan, failed,
                rebuildState);
    }

    private SearchCorpusHealth checkSource(long workspaceId, FtsRebuildState rebuildState) {
        List<SourceSearchAuthorityDocument> documents =
                sourceAuthorityRepository.findAllDocuments(workspaceId);
        Map<Long, SourceSearchDocument> eligible = new HashMap<>();
        Map<Long, List<SourceSearchDocument>> eligibleByDocument = new HashMap<>();
        for (SourceSearchAuthorityDocument document : documents) {
            List<SourceSearchDocument> projections =
                    SourceChunkIndexingService.eligibleDocuments(document);
            eligibleByDocument.put(document.documentId(), projections);
            projections.forEach(projection -> eligible.put(projection.sourceChunkId(), projection));
        }

        List<SourceIndexProjection> rows = ftsRepository.listSourceProjections(workspaceId);
        Map<Long, List<SourceIndexProjection>> valid = new HashMap<>();
        long orphan = ftsRepository.countDanglingIdentities(workspaceId, "SOURCE");
        for (SourceIndexProjection row : rows) {
            Long id = positiveLong(row.sourceChunkId());
            if (!row.identityValid() || id == null || !eligible.containsKey(id)) {
                orphan++;
            } else {
                valid.computeIfAbsent(id, ignored -> new ArrayList<>()).add(row);
            }
        }
        long missing = eligible.keySet().stream()
                .filter(id -> valid.getOrDefault(id, List.of()).size() != 1).count();
        Set<String> stale = new HashSet<>();
        for (Map.Entry<Long, SourceSearchDocument> entry : eligible.entrySet()) {
            List<SourceIndexProjection> matches = valid.getOrDefault(entry.getKey(), List.of());
            if (matches.size() == 1 && !sameProjection(matches.getFirst(), entry.getValue())) {
                stale.add("chunk:" + entry.getKey());
            }
        }

        Map<Long, StoredSourceSearchIndexSync> ledgers = sourceSyncRepository.findAll(workspaceId)
                .stream().collect(Collectors.toMap(StoredSourceSearchIndexSync::documentId,
                        Function.identity()));
        for (SourceSearchAuthorityDocument document : documents) {
            List<SourceSearchDocument> projections = eligibleByDocument.get(document.documentId());
            StoredSourceSearchIndexSync ledger = ledgers.get(document.documentId());
            if (ledger == null) {
                stale.add("document:" + document.documentId());
                continue;
            }
            String fingerprint = SourceChunkIndexingService.fingerprint(projections);
            SourceSearchIndexSyncStatus expected = projections.isEmpty()
                    ? SourceSearchIndexSyncStatus.INELIGIBLE : SourceSearchIndexSyncStatus.SYNCED;
            String expectedIndexedFingerprint = projections.isEmpty() ? null : fingerprint;
            if (ledger.status() != expected
                    || ledger.eligibleChunkCount() != projections.size()
                    || ledger.indexedChunkCount() != projections.size()
                    || !Objects.equals(ledger.canonicalFingerprint(), fingerprint)
                    || !Objects.equals(ledger.indexedFingerprint(), expectedIndexedFingerprint)) {
                stale.add("document:" + document.documentId());
            }
        }
        long indexed = valid.entrySet().stream()
                .filter(entry -> entry.getValue().size() == 1).count();
        return corpusHealth(SearchCorpus.SOURCE, indexed, missing, stale.size(), orphan,
                rebuildFailure(rebuildState), rebuildState);
    }

    private static boolean sameProjection(SourceIndexProjection row, SourceSearchDocument authority) {
        return row.documentId() == authority.documentId()
                && row.chunkNo() == authority.chunkNo()
                && Objects.equals(row.pageNo(), authority.pageNo())
                && Objects.equals(row.normalizedContent(), authority.normalizedContent())
                && Objects.equals(row.section(), authority.section())
                && Objects.equals(row.headingPath(), authority.headingPath())
                && Objects.equals(row.contentHash(), authority.contentHash());
    }

    private static SearchCorpusHealth corpusHealth(SearchCorpus corpus, long indexed, long missing,
                                                   long stale, long orphan, long failed,
                                                   FtsRebuildState rebuildState) {
        SearchHealthStatus status = failed > 0 ? SearchHealthStatus.DEGRADED
                : missing + stale + orphan > 0 ? SearchHealthStatus.REBUILD_REQUIRED
                : SearchHealthStatus.HEALTHY;
        return new SearchCorpusHealth(corpus, status, indexed, missing, stale, orphan, failed,
                rebuildState);
    }

    private static long rebuildFailure(FtsRebuildState state) {
        return state != null && state.status() != FtsRebuildStatus.COMPLETED ? 1 : 0;
    }

    private static long indexed(List<SearchCorpusHealth> corpora, SearchCorpus corpus) {
        return corpora.stream().filter(item -> item.corpus() == corpus)
                .mapToLong(SearchCorpusHealth::indexed).sum();
    }

    private static SearchHealthStatus combinedStatus(List<SearchCorpusHealth> corpora) {
        if (corpora.stream().anyMatch(item -> item.status() == SearchHealthStatus.DEGRADED)) {
            return SearchHealthStatus.DEGRADED;
        }
        if (corpora.stream().anyMatch(item -> item.status() == SearchHealthStatus.REBUILD_REQUIRED)) {
            return SearchHealthStatus.REBUILD_REQUIRED;
        }
        return SearchHealthStatus.HEALTHY;
    }

    private static Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
