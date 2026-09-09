package org.km.llmwiki.rag;

import org.km.llmwiki.ai.embedding.EmbeddingInput;
import org.km.llmwiki.ai.embedding.EmbeddingVector;
import org.km.llmwiki.graph.GraphCanonicalCurrentness;
import org.km.llmwiki.graph.GraphProjectionInputAssembler;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphProjectionLifecycleService;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.persistence.graph.arcadedb.ArcadeDbGraphProjectionBackendFactory;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.processing.ProcessingJobType;
import org.km.llmwiki.search.FtsSearchIndexRepository;
import org.km.llmwiki.search.KnowledgeSearchDocument;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.embedding.EmbeddingEvidenceKind;
import org.km.llmwiki.search.embedding.EmbeddingProjectionIdentity;
import org.km.llmwiki.search.embedding.EmbeddingProjectionReadinessRepository;
import org.km.llmwiki.search.embedding.EmbeddingProjectionRepository;
import org.km.llmwiki.search.vector.VectorCandidateSearchService;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.WikiContentHash;
import org.km.llmwiki.wiki.WikiPageType;
import org.km.llmwiki.wiki.WikiPathContract;
import org.km.llmwiki.workspace.CreateWorkspaceRequest;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared deterministic fixture for the graph-grounded retrieval quality gate and the offline
 * fusion ranking calibration. It materializes versioned golden-corpus pages into a real
 * workspace (SQLite knowledge pages, FTS rows, index sync), replays the production
 * full-then-incremental embedding projection flow through the repository contracts, applies the
 * stale-hash safety mutation, and wires the production-equivalent retrieval services over a
 * real ArcadeDB lifecycle with the deterministic embedding/vector fixtures.
 */
final class GraphRetrievalQualityFixture {

    static final String NOW = "2026-09-09T00:00:00Z";

    private final JdbcClient db;
    private final WorkspaceService workspaces;
    private final SearchService searchService;
    private final PublishedWikiRepository publishedWikiRepository;
    private final PublishedWikiContentReader publishedWikiContentReader;
    private final SourceSearchAuthorityRepository sourceAuthorityRepository;
    private final FtsSearchIndexRepository ftsRepository;
    private final EmbeddingProjectionRepository embeddingRepository;
    private final EmbeddingProjectionReadinessRepository embeddingReadiness;
    private final ProcessingJobRepository jobs;
    private final GraphProjectionInputAssembler assembler;
    private final GraphProjectionLifecycleRepository lifecycleRepository;
    private final GraphCanonicalCurrentness currentness;
    private final WikiPathContract paths;
    private final DeterministicConceptEmbeddingClient embedder =
            new DeterministicConceptEmbeddingClient();

    record CorpusMaterialization(GraphWorkspaceScope active, GraphWorkspaceScope foreign,
                                 Map<String, String> pageHashes) {
    }

    record LifecycleBundle(GraphProjectionLifecycleService lifecycle,
                           ArcadeDbGraphProjectionBackendFactory factory)
            implements AutoCloseable {
        @Override
        public void close() {
            lifecycle.close();
        }
    }

    GraphRetrievalQualityFixture(JdbcClient db,
                                 WorkspaceService workspaces,
                                 SearchService searchService,
                                 PublishedWikiRepository publishedWikiRepository,
                                 PublishedWikiContentReader publishedWikiContentReader,
                                 SourceSearchAuthorityRepository sourceAuthorityRepository,
                                 FtsSearchIndexRepository ftsRepository,
                                 EmbeddingProjectionRepository embeddingRepository,
                                 EmbeddingProjectionReadinessRepository embeddingReadiness,
                                 ProcessingJobRepository jobs,
                                 GraphProjectionInputAssembler assembler,
                                 GraphProjectionLifecycleRepository lifecycleRepository,
                                 GraphCanonicalCurrentness currentness,
                                 WikiPathContract paths) {
        this.db = db;
        this.workspaces = workspaces;
        this.searchService = searchService;
        this.publishedWikiRepository = publishedWikiRepository;
        this.publishedWikiContentReader = publishedWikiContentReader;
        this.sourceAuthorityRepository = sourceAuthorityRepository;
        this.ftsRepository = ftsRepository;
        this.embeddingRepository = embeddingRepository;
        this.embeddingReadiness = embeddingReadiness;
        this.jobs = jobs;
        this.assembler = assembler;
        this.lifecycleRepository = lifecycleRepository;
        this.currentness = currentness;
        this.paths = paths;
    }

    /**
     * Materializes the corpus pages into two workspaces (active + foreign), indexes embeddings
     * for the embedded pages, and republishes the stale-hash safety page so its indexed rows go
     * stale. {@code pageGroups} holds the page lists to write; only pages with
     * {@code embedded()=true} receive a projection row.
     */
    CorpusMaterialization materializeCorpus(Path temp, String activeWorkspaceName,
                                            List<org.km.llmwiki.rag.GraphRetrievalGoldenCorpus.GoldenPage> pageGroups)
            throws Exception {
        GraphWorkspaceScope active = workspace(temp, activeWorkspaceName);
        GraphWorkspaceScope foreign = workspace(temp, activeWorkspaceName + "-foreign");
        workspaces.open(active.id());
        Map<String, String> pageHashes = new HashMap<>();
        for (org.km.llmwiki.rag.GraphRetrievalGoldenCorpus.GoldenPage page : pageGroups) {
            pageHashes.put(page.knowledgeId(), wiki(active, page));
        }
        for (org.km.llmwiki.rag.GraphRetrievalGoldenCorpus.GoldenPage page
                : new GraphRetrievalGoldenCorpus().foreignWorkspacePages()) {
            wiki(foreign, page);
        }
        indexEmbeddings(active.id(), pageHashes,
                pageGroups.stream().filter(
                        org.km.llmwiki.rag.GraphRetrievalGoldenCorpus.GoldenPage::embedded).toList());
        return new CorpusMaterialization(active, foreign, pageHashes);
    }

    /**
     * Safety mutation: an external editor republishes the page (vault file and DB hash move
     * together), so the FTS and embedding rows hold the old hash and authority revalidation
     * must reject the page in every mode.
     */
    void mutateStaleHash(GraphWorkspaceScope active,
                         org.km.llmwiki.rag.GraphRetrievalGoldenCorpus.GoldenPage page)
            throws Exception {
        String mutatedContent = new StringBuilder("---\n")
                .append("id: \"").append(page.knowledgeId()).append("\"\n")
                .append("title: \"").append(page.title()).append("\"\n")
                .append("type: \"CONCEPT\"\nstatus: \"PUBLISHED\"\n")
                .append("aliases: []\n")
                .append(renderList("tags", page.tags()))
                .append("sources: []\n")
                .append("created_at: \"").append(NOW).append("\"\n")
                .append("updated_at: \"").append(NOW).append("\"\n")
                .append("---\n\n# ").append(page.title()).append('\n')
                .append(page.body()).append("\n外部編輯後的修訂內容。").toString();
        Path stalePath = Path.of(workspaces.get(active.id()).vaultPath())
                .resolve(paths.resolveLogicalPath(WikiPageType.CONCEPT, page.title())
                        .substring("vault/".length()));
        Files.writeString(stalePath, mutatedContent);
        db.sql("UPDATE knowledge_page SET content_hash=? WHERE workspace_id=? AND knowledge_id=?")
                .params(WikiContentHash.sha256(mutatedContent.getBytes(StandardCharsets.UTF_8)),
                        active.id(), page.knowledgeId()).update();
    }

    LifecycleBundle lifecycle(Path path) {
        ArcadeDbGraphProjectionBackendFactory factory =
                new ArcadeDbGraphProjectionBackendFactory(path, GraphProjectionVersion.current());
        return new LifecycleBundle(new GraphProjectionLifecycleService(true, "arcadedb",
                GraphProjectionVersion.current(), lifecycleRepository, factory, currentness),
                factory);
    }

    FusedRetrievalOrchestrator orchestrator(GraphProjectionLifecycleService lifecycle,
                                            ArcadeDbGraphProjectionBackendFactory factory,
                                            FusionRankingPolicy rankingPolicy) {
        FusedEvidenceService fusion = new FusedEvidenceService(workspaces, searchService,
                vectorService(), publishedWikiRepository, publishedWikiContentReader,
                sourceAuthorityRepository, lifecycle,
                new org.km.llmwiki.graph.GraphTraversalService(lifecycle, factory),
                new GraphEvidenceAdmissionService(lifecycle, publishedWikiRepository,
                        publishedWikiContentReader, sourceAuthorityRepository),
                rankingPolicy);
        return new FusedRetrievalOrchestrator(fusion, publishedWikiRepository,
                publishedWikiContentReader, sourceAuthorityRepository, lifecycle);
    }

    RetrievalService retrievalService(FusedRetrievalOrchestrator orchestrator) {
        return new RetrievalService(workspaces, searchService, publishedWikiRepository,
                publishedWikiContentReader, sourceAuthorityRepository, vectorService(),
                new ReciprocalRankFusion(), orchestrator);
    }

    VectorCandidateSearchService vectorService() {
        return new VectorCandidateSearchService(embedder, publishedWikiRepository,
                sourceAuthorityRepository, new DeterministicVectorSimilaritySearch(db),
                embeddingReadiness);
    }

    DeterministicConceptEmbeddingClient embedder() {
        return embedder;
    }

    private GraphWorkspaceScope workspace(Path temp, String name) {
        return new GraphWorkspaceScope(workspaces.create(new CreateWorkspaceRequest(name,
                temp.resolve(name).toString())).id());
    }

    private String wiki(GraphWorkspaceScope scope,
                        org.km.llmwiki.rag.GraphRetrievalGoldenCorpus.GoldenPage page)
            throws Exception {
        String content = new StringBuilder("---\n")
                .append("id: \"").append(page.knowledgeId()).append("\"\n")
                .append("title: \"").append(page.title()).append("\"\n")
                .append("type: \"CONCEPT\"\nstatus: \"PUBLISHED\"\n")
                .append("aliases: []\n")
                .append(renderList("tags", page.tags()))
                .append("sources: []\n")
                .append("created_at: \"").append(NOW).append("\"\n")
                .append("updated_at: \"").append(NOW).append("\"\n")
                .append("---\n\n# ").append(page.title()).append('\n').append(page.body())
                .toString();
        String logicalPath = paths.resolveLogicalPath(WikiPageType.CONCEPT, page.title());
        Path target = Path.of(workspaces.get(scope.id()).vaultPath())
                .resolve(logicalPath.substring("vault/".length()));
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        String hash = WikiContentHash.sha256(content.getBytes(StandardCharsets.UTF_8));
        KeyHolder pageKey = new GeneratedKeyHolder();
        db.sql("""
                INSERT INTO knowledge_page(workspace_id, knowledge_id, title, normalized_title,
                    type, markdown_path, status, content_hash, revision, created_at, updated_at)
                VALUES(?, ?, ?, ?, 'CONCEPT', ?, 'PUBLISHED', ?, 1, ?, ?)
                """).params(scope.id(), page.knowledgeId(), page.title(),
                org.km.llmwiki.wiki.WikiTargetReference.normalizeTitle(page.title()), logicalPath,
                hash, NOW, NOW).update(pageKey);
        ftsRepository.upsertKnowledge(new KnowledgeSearchDocument(scope.id(), page.knowledgeId(),
                page.title(), org.km.llmwiki.wiki.WikiTargetReference.normalizeTitle(page.title()),
                page.body(), logicalPath, "CONCEPT", "PUBLISHED", hash));
        db.sql("""
                INSERT INTO knowledge_search_index_sync
                    (workspace_id, knowledge_page_id, knowledge_id, status, content_hash,
                     indexed_content_hash, indexed_revision, failure_detail, updated_at)
                VALUES (?, ?, ?, 'SYNCED', ?, ?, 1, NULL, ?)
                """).params(scope.id(), pageKey.getKey().longValue(), page.knowledgeId(), hash,
                hash, NOW).update();
        return hash;
    }

    private void indexEmbeddings(long workspaceId, Map<String, String> pageHashes,
                                 List<org.km.llmwiki.rag.GraphRetrievalGoldenCorpus.GoldenPage> embeddedPages)
            throws Exception {
        for (org.km.llmwiki.rag.GraphRetrievalGoldenCorpus.GoldenPage page : embeddedPages) {
            List<Double> vector = embedder.embedText(page.title() + " " + page.body());
            byte[] blob = org.km.llmwiki.search.embedding.EmbeddingVectorCodec.encode(
                    new EmbeddingVector(EmbeddingInput.identityFor(page.body()), vector));
            embeddingRepository.upsertFresh(new EmbeddingProjectionIdentity(workspaceId,
                    EmbeddingEvidenceKind.WIKI, page.knowledgeId(),
                    pageHashes.get(page.knowledgeId()),
                    DeterministicConceptEmbeddingClient.PROVIDER,
                    DeterministicConceptEmbeddingClient.MODEL,
                    DeterministicConceptEmbeddingClient.DIMENSION,
                    DeterministicConceptEmbeddingClient.PROJECTION_VERSION), blob, NOW);
        }
        markProjectionReady(workspaceId, "wiki", EmbeddingEvidenceKind.WIKI, embeddedPages.size());
        markProjectionReady(workspaceId, "sources", EmbeddingEvidenceKind.SOURCE_CHUNK, 0);
    }

    private void markProjectionReady(long workspaceId, String jobName,
                                     EmbeddingEvidenceKind corpus, int expected) {
        // An incremental operation can only establish READY on top of a completed FULL
        // baseline; the fixture therefore replays the production full-then-incremental flow.
        long fullJob = jobs.create(workspaceId, "quality-full-" + workspaceId + "-" + jobName,
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long fullGeneration = embeddingReadiness.markQueued(workspaceId, fullJob, corpus, 0);
        embeddingReadiness.markRunning(workspaceId, fullJob, corpus);
        embeddingReadiness.markCompletedForGeneration(workspaceId, fullJob, corpus, fullGeneration,
                expected, expected, 0, DeterministicConceptEmbeddingClient.PROVIDER,
                DeterministicConceptEmbeddingClient.MODEL,
                DeterministicConceptEmbeddingClient.DIMENSION, true, "fixture-snapshot-proof");
        if (expected == 0) {
            return;
        }
        long jobId = jobs.create(workspaceId, "quality-" + workspaceId + "-" + jobName,
                ProcessingJobType.EMBEDDING_REBUILD, 1).id();
        long generation = embeddingReadiness.markQueued(workspaceId, jobId, corpus, expected);
        embeddingReadiness.markRunning(workspaceId, jobId, corpus);
        embeddingReadiness.markCompletedForGeneration(workspaceId, jobId, corpus, generation,
                expected, expected, 0, DeterministicConceptEmbeddingClient.PROVIDER,
                DeterministicConceptEmbeddingClient.MODEL,
                DeterministicConceptEmbeddingClient.DIMENSION, true, "fixture-snapshot-proof");
    }

    private static String renderList(String field, List<String> values) {
        if (values.isEmpty()) {
            return field + ": []\n";
        }
        StringBuilder result = new StringBuilder(field).append(":\n");
        values.forEach(value -> result.append("  - \"").append(value).append("\"\n"));
        return result.toString();
    }
}
