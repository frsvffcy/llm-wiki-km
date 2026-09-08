package org.km.llmwiki.persistence.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.GraphCanonicalCurrentness;
import org.km.llmwiki.graph.GraphProjectionBackendFactory;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionInputAssembler;
import org.km.llmwiki.graph.GraphProjectionIngressService;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphProjectionLifecycleService;
import org.km.llmwiki.graph.GraphProjectionOperationKind;
import org.km.llmwiki.graph.GraphProjectionStatusResponse;
import org.km.llmwiki.graph.GraphProjectionVerificationStatus;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.persistence.graph.arcadedb.ArcadeDbGraphProjectionBackendFactory;
import org.km.llmwiki.rag.EvidenceBundle;
import org.km.llmwiki.rag.FusedEvidenceService;
import org.km.llmwiki.rag.FusedRetrievalOrchestrator;
import org.km.llmwiki.rag.GraphEvidenceAdmissionService;
import org.km.llmwiki.rag.RetrievalMode;
import org.km.llmwiki.search.FtsSearchIndexRepository;
import org.km.llmwiki.search.KnowledgeSearchDocument;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.vector.VectorCandidateSearchService;
import org.km.llmwiki.source.DocumentRepository;
import org.km.llmwiki.source.SourceChunkRepository;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.WikiContentHash;
import org.km.llmwiki.wiki.WikiPageType;
import org.km.llmwiki.wiki.WikiPathContract;
import org.km.llmwiki.workspace.CreateWorkspaceRequest;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Operational API application evidence over the production backend: the provider-neutral
 * operations port (readiness/rebuild/repair), its safe status projection, deterministic
 * concurrency safety, and the read-only Ask boundary. No sleep, no timing luck.
 */
class GraphProjectionOperationalApiIntegrationTest extends IsolatedIntegrationTest {

    private static final GraphProjectionVersion VERSION = GraphProjectionVersion.current();

    @TempDir
    Path temp;

    @Autowired GraphProjectionInputAssembler assembler;
    @Autowired GraphCanonicalCurrentness currentness;
    @Autowired GraphProjectionLifecycleRepository repository;
    @Autowired WorkspaceService workspaces;
    @Autowired WikiPathContract paths;
    @Autowired PublishedWikiRepository publishedWikiRepository;
    @Autowired PublishedWikiContentReader publishedWikiContentReader;
    @Autowired SourceSearchAuthorityRepository sourceAuthorityRepository;
    @Autowired SearchService searchService;
    @Autowired VectorCandidateSearchService vectorCandidateSearchService;
    @Autowired FtsSearchIndexRepository ftsRepository;
    @Autowired DocumentRepository documents;
    @Autowired SourceChunkRepository chunks;

    @Test
    void operationalApiRebuildReadinessAndRepairOverProductionBackend() throws Exception {
        GraphWorkspaceScope scope = workspace("operational");
        wiki(scope, "wiki-ops", "Ops Page", "operational authority 內容");
        String fingerprint = assembler.assemble(scope).sourceFingerprint();

        try (var lifecycle = lifecycle(temp.resolve("operational-graph"))) {
            GraphProjectionIngressService operations = new GraphProjectionIngressService(assembler,
                    lifecycle);
            ObjectMapper mapper = new ObjectMapper();

            GraphProjectionStatusResponse rebuilt = GraphProjectionStatusResponse.from(
                    operations.rebuild(scope.id()));
            assertThat(rebuilt.status()).isEqualTo("READY");
            assertThat(rebuilt.provider()).isEqualTo("arcadedb");
            assertThat(rebuilt.projectionVersion()).isEqualTo("graph-projection-v2");
            assertThat(rebuilt.appliedGeneration()).isEqualTo(1);
            assertThat(rebuilt.repairRecommended()).isFalse();
            String rebuiltJson = mapper.writeValueAsString(rebuilt);
            assertThat(rebuiltJson).doesNotContain(fingerprint).doesNotContain("snapshotToken")
                    .doesNotContain("Fingerprint").doesNotContain("vault/");

            GraphProjectionStatusResponse readiness = GraphProjectionStatusResponse.from(
                    operations.readiness(scope.id()));
            assertThat(readiness.status()).isEqualTo("READY");

            // Canonical deletion invalidates the applied proof; the operational API reports the
            // degraded state with a typed failure code instead of pretending readiness.
            db().sql("UPDATE knowledge_page SET status='DELETED' WHERE workspace_id=? AND knowledge_id=?")
                    .params(scope.id(), "wiki-ops").update();
            GraphProjectionStatusResponse stale = GraphProjectionStatusResponse.from(
                    operations.readiness(scope.id()));
            assertThat(stale.status()).isEqualTo("STALE");
            assertThat(stale.failureCode()).isEqualTo("GRAPH_PROJECTION_STALE");
            assertThat(stale.repairRecommended()).isTrue();
            assertThat(mapper.writeValueAsString(stale)).doesNotContain(fingerprint);

            GraphProjectionStatusResponse repaired = GraphProjectionStatusResponse.from(
                    operations.repair(scope.id()));
            assertThat(repaired.status()).isEqualTo("READY");
            assertThat(repaired.appliedGeneration()).isEqualTo(2);
            assertThat(repaired.repairRecommended()).isFalse();
        }
    }

    @Test
    void lateRebuildCompletionThroughTheOperationalApiCannotReplaceNewerGeneration() {
        GraphWorkspaceScope scope = workspace("late-completion");
        long doc = document(scope);
        chunk(doc, "A");
        var factory = factory(temp.resolve("late-graph"));
        try (var newer = new GraphProjectionLifecycleService(true, "arcadedb", VERSION,
                repository, factory, currentness)) {
            GraphCanonicalCurrentness racing = new GraphCanonicalCurrentness() {
                @Override
                public <T> T withCurrent(GraphWorkspaceScope workspace, String fingerprint,
                                         java.util.function.Supplier<T> action) {
                    chunk(doc, "B");
                    assertThat(newer.rebuild(assembler.assemble(workspace)).ready()).isTrue();
                    return currentness.withCurrent(workspace, fingerprint, action);
                }
            };
            try (var older = new GraphProjectionLifecycleService(true, "arcadedb", VERSION,
                    repository, factory, racing)) {
                GraphProjectionIngressService operations = new GraphProjectionIngressService(
                        assembler, older);

                // The late rebuild A completes after the newer rebuild B published: the
                // lifecycle CAS must reject A typed instead of overwriting B, and the
                // operational surface must keep reporting generation B as READY.
                assertThatThrownBy(() -> operations.rebuild(scope.id()))
                        .isInstanceOfSatisfying(GraphProjectionException.class, failure ->
                                assertThat(failure.failureType())
                                        .isEqualTo(GraphProjectionFailureType.PROJECTION_STALE));
                GraphProjectionStatusResponse status = GraphProjectionStatusResponse.from(
                        new GraphProjectionIngressService(assembler, newer).readiness(scope.id()));
                assertThat(status.status()).isEqualTo("READY");
                assertThat(status.appliedGeneration()).isEqualTo(2);
            }
        }
    }

    @Test
    void concurrentOperationalRebuildsStayTypedAndMonotonic() throws Exception {
        GraphWorkspaceScope scope = workspace("concurrent-ops");
        wiki(scope, "wiki-concurrent", "Concurrent Page", "concurrent authority 內容");
        try (var lifecycle = lifecycle(temp.resolve("concurrent-graph"))) {
            GraphProjectionIngressService operations = new GraphProjectionIngressService(assembler,
                    lifecycle);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                List<Future<Object>> outcomes = new ArrayList<>();
                for (int index = 0; index < 2; index++) {
                    outcomes.add(executor.submit(() -> {
                        start.await();
                        try {
                            return GraphProjectionStatusResponse.from(operations.rebuild(scope.id()));
                        } catch (GraphProjectionException typed) {
                            return typed;
                        }
                    }));
                }
                start.countDown();
                List<Object> results = new ArrayList<>();
                for (Future<Object> outcome : outcomes) {
                    results.add(outcome.get(30, TimeUnit.SECONDS));
                }

                // Every concurrent outcome completes deterministically: an untyped runtime
                // fault would fail the future (and this test), while typed operational
                // contention (an older rebuild losing the CAS, or the single backend session
                // refusing a second open) must stay inside the projection failure taxonomy
                // and never leave a mixed generation behind.
                results.stream()
                        .filter(GraphProjectionException.class::isInstance)
                        .map(GraphProjectionException.class::cast)
                        .forEach(failure -> assertThat(failure.failureType())
                                .isIn(GraphProjectionFailureType.BACKEND_LOCKED,
                                        GraphProjectionFailureType.PROJECTION_STALE,
                                        GraphProjectionFailureType.TRANSACTION_FAILURE));

                // Recovery: a subsequent explicit rebuild always converges to READY with a
                // strictly monotonic generation, regardless of the concurrent outcome.
                GraphProjectionStatusResponse recovered = GraphProjectionStatusResponse.from(
                        operations.rebuild(scope.id()));
                assertThat(recovered.status()).isEqualTo("READY");
                long recoveredGeneration = recovered.appliedGeneration();
                GraphProjectionStatusResponse again = GraphProjectionStatusResponse.from(
                        operations.rebuild(scope.id()));
                assertThat(again.status()).isEqualTo("READY");
                assertThat(again.appliedGeneration()).isGreaterThan(recoveredGeneration);
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void activeOperationIsReportedAsNotReadyAndAskRetrievalNeverTriggersARebuild()
            throws Exception {
        GraphWorkspaceScope scope = workspace("explicit-only");
        wiki(scope, "wiki-explicit", "Explicit Page", "explicit authority 內容");
        String fingerprint = assembler.assemble(scope).sourceFingerprint();
        repository.reserve(scope, "arcadedb", VERSION, GraphProjectionOperationKind.REBUILD,
                fingerprint, "owner-token");
        try (var lifecycle = lifecycle(temp.resolve("explicit-graph"))) {
            GraphProjectionIngressService operations = new GraphProjectionIngressService(assembler,
                    lifecycle);

            GraphProjectionStatusResponse status = GraphProjectionStatusResponse.from(
                    operations.readiness(scope.id()));
            assertThat(status.status()).isEqualTo("BUILDING");
            assertThat(status.operationKind()).isEqualTo("REBUILD");
            assertThat(status.appliedGeneration()).isZero();

            // HYBRID_GRAPH retrieval observes the lifecycle readiness port only: the degraded
            // graph channel must leave the planted operation and its generation untouched.
            FusedEvidenceService fusion = new FusedEvidenceService(workspaces, searchService,
                    vectorCandidateSearchService, publishedWikiRepository,
                    publishedWikiContentReader, sourceAuthorityRepository, lifecycle,
                    new org.km.llmwiki.graph.GraphTraversalService(lifecycle,
                            factory(temp.resolve("explicit-graph"))),
                    new GraphEvidenceAdmissionService(lifecycle, publishedWikiRepository,
                            publishedWikiContentReader, sourceAuthorityRepository));
            FusedRetrievalOrchestrator orchestrator = new FusedRetrievalOrchestrator(fusion,
                    publishedWikiRepository, publishedWikiContentReader, sourceAuthorityRepository,
                    lifecycle);

            EvidenceBundle bundle = orchestrator.retrieveFused(org.km.llmwiki.rag.RetrievalRequest
                    .defaults("explicit authority", RetrievalMode.HYBRID_GRAPH));

            assertThat(bundle.items()).extracting(item -> item.stableId()).contains("wiki-explicit");
            assertThat(bundle.diagnostics().graphUnavailable()).isTrue();
            assertThat(bundle.diagnostics().graphDegraded()).isFalse();

            GraphProjectionStatusResponse after = GraphProjectionStatusResponse.from(
                    operations.readiness(scope.id()));
            assertThat(after.status()).isEqualTo("BUILDING");
            assertThat(after.targetGeneration()).isEqualTo(status.targetGeneration());
            assertThat(after.appliedGeneration()).isZero();
        }
    }

    private ArcadeDbGraphProjectionBackendFactory factory(Path path) {
        return new ArcadeDbGraphProjectionBackendFactory(path, VERSION);
    }

    private GraphProjectionLifecycleService lifecycle(Path path) {
        return new GraphProjectionLifecycleService(true, "arcadedb", VERSION, repository,
                factory(path), currentness);
    }

    private GraphWorkspaceScope workspace(String name) {
        return new GraphWorkspaceScope(workspaces.create(new CreateWorkspaceRequest(name,
                temp.resolve(name).toString())).id());
    }

    private long document(GraphWorkspaceScope scope) {
        long id = documents.insert(scope.id(), "source.txt", "source.txt", "txt", "inbox/source.txt",
                WikiContentHash.sha256("original"), 8L, "text/plain", "2026-09-09T00:00:00Z",
                "PROCESSED", null, null);
        documents.markExtractionSucceeded(id, WikiContentHash.sha256("content"));
        return id;
    }

    private void chunk(long doc, String content) {
        chunks.deleteByDocumentId(doc);
        db().sql("""
                INSERT INTO source_chunk(document_id, chunk_no, content, normalized_content,
                    content_hash, created_at, updated_at) VALUES(?, 1, ?, ?, ?,
                    '2026-09-09T00:00:00Z', '2026-09-09T00:00:00Z')
                """).params(doc, content, content, WikiContentHash.sha256(content)).update();
    }

    private void wiki(GraphWorkspaceScope scope, String knowledgeId, String title, String body)
            throws Exception {
        String content = new StringBuilder("---\n")
                .append("id: \"").append(knowledgeId).append("\"\n")
                .append("title: \"").append(title).append("\"\n")
                .append("type: \"CONCEPT\"\nstatus: \"PUBLISHED\"\n")
                .append("aliases: []\n")
                .append("tags: []\n")
                .append("sources: []\n")
                .append("created_at: \"2026-09-09T00:00:00Z\"\n")
                .append("updated_at: \"2026-09-09T00:00:00Z\"\n")
                .append("---\n\n# ").append(title).append('\n').append(body).toString();
        String logicalPath = paths.resolveLogicalPath(WikiPageType.CONCEPT, title);
        Path target = Path.of(workspaces.get(scope.id()).vaultPath())
                .resolve(logicalPath.substring("vault/".length()));
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        String hash = WikiContentHash.sha256(content.getBytes(StandardCharsets.UTF_8));
        KeyHolder pageKey = new GeneratedKeyHolder();
        db().sql("""
                INSERT INTO knowledge_page(workspace_id, knowledge_id, title, normalized_title,
                    type, markdown_path, status, content_hash, revision, created_at, updated_at)
                VALUES(?, ?, ?, ?, 'CONCEPT', ?, 'PUBLISHED', ?, 1,
                    '2026-09-09T00:00:00Z', '2026-09-09T00:00:00Z')
                """).params(scope.id(), knowledgeId, title,
                org.km.llmwiki.wiki.WikiTargetReference.normalizeTitle(title), logicalPath,
                hash).update(pageKey);
        ftsRepository.upsertKnowledge(new KnowledgeSearchDocument(scope.id(), knowledgeId,
                title, org.km.llmwiki.wiki.WikiTargetReference.normalizeTitle(title), body,
                logicalPath, "CONCEPT", "PUBLISHED", hash));
        db().sql("""
                INSERT INTO knowledge_search_index_sync
                    (workspace_id, knowledge_page_id, knowledge_id, status, content_hash,
                     indexed_content_hash, indexed_revision, failure_detail, updated_at)
                VALUES (?, ?, ?, 'SYNCED', ?, ?, 1, NULL, '2026-09-09T00:00:00Z')
                """).params(scope.id(), pageKey.getKey().longValue(), knowledgeId, hash,
                hash).update();
    }
}
