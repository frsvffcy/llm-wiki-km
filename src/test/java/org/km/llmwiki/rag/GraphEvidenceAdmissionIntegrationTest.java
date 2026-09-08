package org.km.llmwiki.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.GraphCanonicalCurrentness;
import org.km.llmwiki.graph.GraphEntity;
import org.km.llmwiki.graph.GraphEntityIdentity;
import org.km.llmwiki.graph.GraphEntityType;
import org.km.llmwiki.graph.GraphProjectionBackendFactory;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionInputAssembler;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphProjectionLifecycleService;
import org.km.llmwiki.graph.GraphProjectionSnapshot;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.graph.GraphRelationType;
import org.km.llmwiki.graph.GraphTraversalOrdering;
import org.km.llmwiki.graph.GraphTraversalQuery;
import org.km.llmwiki.graph.GraphTraversalResult;
import org.km.llmwiki.graph.GraphTraversalService;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.persistence.graph.arcadedb.ArcadeDbGraphProjectionBackendFactory;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Production-evidence path: ArcadeDB projection → bounded traversal → consumption-window
 * revalidation → canonical authority revalidation → EvidenceBundle-compatible evidence items.
 * The Spring context keeps the graph capability disabled, so the enabled paths build their own
 * production adapter exactly like the existing canonical traversal evidence.
 */
class GraphEvidenceAdmissionIntegrationTest extends IsolatedIntegrationTest {

    private static final GraphProjectionVersion VERSION = GraphProjectionVersion.initial();

    @TempDir
    Path temp;

    @Autowired GraphProjectionInputAssembler assembler;
    @Autowired GraphCanonicalCurrentness currentness;
    @Autowired GraphProjectionLifecycleRepository repository;
    @Autowired WorkspaceService workspaces;
    @Autowired DocumentRepository documents;
    @Autowired SourceChunkRepository chunks;
    @Autowired WikiPathContract paths;
    @Autowired PublishedWikiRepository publishedWikiRepository;
    @Autowired PublishedWikiContentReader publishedWikiContentReader;
    @Autowired SourceSearchAuthorityRepository sourceAuthorityRepository;
    @Autowired RetrievalService retrievalService;
    @Autowired GraphEvidenceAdmissionService disabledContextAdmission;

    @Test
    void admittedTraversalEvidenceCarriesCanonicalIdentityAndTypedRejections() throws Exception {
        GraphWorkspaceScope workspace = workspace("admission");
        long document = document(workspace, "source.txt");
        chunk(document, "A".repeat(400));
        wiki(workspace, "wiki-target", "Target Page", List.of(), List.of(), "目標內容");
        wiki(workspace, "wiki-source", "Source Page", List.of("rag"), List.of(document),
                "[[Target Page|目標頁面]]");
        GraphProjectionInput input = assembler.assemble(workspace);
        GraphEntityIdentity wikiSeed = input.entities().stream()
                .filter(entity -> entity.identity().type() == GraphEntityType.WIKI_PAGE)
                .filter(entity -> entity.provenance().authority().stableId()
                        .equals("wiki-source"))
                .findFirst().orElseThrow().identity();
        EnumSet<GraphRelationType> allowed = EnumSet.of(GraphRelationType.LINKS_TO,
                GraphRelationType.TAGGED_WITH, GraphRelationType.DERIVED_FROM);

        try (var lifecycle = lifecycle(factory(temp.resolve("admission")))) {
            GraphProjectionSnapshot snapshot = lifecycle.rebuild(input)
                    .controlPlane().appliedSnapshot();
            GraphTraversalResult traversal = new GraphTraversalService(lifecycle, factory(
                    temp.resolve("admission"))).traverse(query(workspace, wikiSeed, snapshot,
                    allowed));
            GraphEvidenceAdmissionService admission = admission(lifecycle);
            GraphEvidenceAdmissionResult admitted = admission.admit(
                    GraphEvidenceAdmissionRequest.of(new EvidenceWorkspace(workspace.id(),
                            "admission"), traversal));

            assertThat(admitted.evidenceItems()).hasSize(1);
            EvidenceItem item = admitted.evidenceItems().getFirst();
            assertThat(item.kind()).isEqualTo(EvidenceKind.WIKI);
            assertThat(item.stableIdentity()).isEqualTo("WIKI:wiki-target");
            assertThat(item.contentHash()).isNotBlank();
            assertThat(item.content()).contains("Target Page");
            assertThat(item.revision()).isEqualTo(1);
            assertThat(admitted.rejections()).extracting(GraphCandidateRejection::reason)
                    .containsExactlyInAnyOrder(GraphEvidenceRejectionReason.NON_EVIDENCE_ENTITY,
                            GraphEvidenceRejectionReason.NON_CITATION_AUTHORITY);
            assertThat(admitted.candidateCount()).isEqualTo(3);
            assertThat(admitted.budgetTruncated()).isFalse();
        }
    }

    @Test
    void sourceChunkTraversalBecomesCanonicalChunkEvidence() {
        GraphWorkspaceScope workspace = workspace("chunk-evidence");
        long document = document(workspace, "chunks.txt");
        chunk(document, "可驗證的 chunk 內容");
        GraphProjectionInput input = assembler.assemble(workspace);
        GraphEntityIdentity documentSeed = input.entities().stream()
                .filter(entity -> entity.identity().type() == GraphEntityType.SOURCE_DOCUMENT)
                .findFirst().orElseThrow().identity();
        long canonicalChunkId = sourceAuthorityRepository
                .findDocument(workspace.id(), document).orElseThrow().chunks()
                .getFirst().sourceChunkId();

        try (var lifecycle = lifecycle(factory(temp.resolve("chunk-evidence")))) {
            GraphProjectionSnapshot snapshot = lifecycle.rebuild(input)
                    .controlPlane().appliedSnapshot();
            GraphTraversalResult traversal = new GraphTraversalService(lifecycle, factory(
                    temp.resolve("chunk-evidence"))).traverse(query(workspace, documentSeed,
                    snapshot, EnumSet.of(GraphRelationType.CONTAINS)));
            GraphEvidenceAdmissionResult admitted = admission(lifecycle).admit(
                    GraphEvidenceAdmissionRequest.of(new EvidenceWorkspace(workspace.id(),
                            "chunk-evidence"), traversal));

            assertThat(admitted.evidenceItems()).hasSize(1);
            EvidenceItem item = admitted.evidenceItems().getFirst();
            assertThat(item.kind()).isEqualTo(EvidenceKind.SOURCE_CHUNK);
            assertThat(item.stableIdentity())
                    .isEqualTo("SOURCE_CHUNK:" + canonicalChunkId);
            assertThat(item.sourceChunkId()).isEqualTo(canonicalChunkId);
            assertThat(item.documentId()).isEqualTo(document);
            assertThat(item.content()).contains("可驗證的 chunk 內容");
            assertThat(admitted.rejections()).isEmpty();
        }
    }

    @Test
    void canonicalMutationBetweenTraversalAndAdmissionFailsClosed() {
        GraphWorkspaceScope workspace = workspace("mutation-race");
        long document = document(workspace, "race.txt");
        chunk(document, "A");
        GraphProjectionInput input = assembler.assemble(workspace);
        GraphEntityIdentity documentSeed = input.entities().stream()
                .filter(entity -> entity.identity().type() == GraphEntityType.SOURCE_DOCUMENT)
                .findFirst().orElseThrow().identity();

        try (var lifecycle = lifecycle(factory(temp.resolve("mutation-race")))) {
            GraphProjectionSnapshot snapshot = lifecycle.rebuild(input)
                    .controlPlane().appliedSnapshot();
            GraphTraversalResult traversal = new GraphTraversalService(lifecycle, factory(
                    temp.resolve("mutation-race"))).traverse(query(workspace, documentSeed,
                    snapshot, EnumSet.of(GraphRelationType.CONTAINS)));
            // The canonical mutation happens after traversal returns and before admission runs:
            // the whole batch must fail closed instead of admitting stale graph candidates.
            chunk(document, "B");

            assertThatThrownBy(() -> admission(lifecycle).admit(
                    GraphEvidenceAdmissionRequest.of(new EvidenceWorkspace(workspace.id(),
                            "mutation-race"), traversal)))
                    .isInstanceOf(GraphProjectionException.class)
                    .extracting(failure -> ((GraphProjectionException) failure).failureType())
                    .isEqualTo(GraphProjectionFailureType.PROJECTION_STALE);
        }
    }

    @Test
    void projectionRebuildBetweenTraversalAndAdmissionFailsClosed() {
        GraphWorkspaceScope workspace = workspace("rebuild-race");
        long document = document(workspace, "race.txt");
        chunk(document, "A");
        GraphProjectionInput input = assembler.assemble(workspace);
        GraphEntityIdentity documentSeed = input.entities().stream()
                .filter(entity -> entity.identity().type() == GraphEntityType.SOURCE_DOCUMENT)
                .findFirst().orElseThrow().identity();

        try (var lifecycle = lifecycle(factory(temp.resolve("rebuild-race")))) {
            GraphProjectionSnapshot snapshotA = lifecycle.rebuild(input)
                    .controlPlane().appliedSnapshot();
            GraphTraversalResult traversal = new GraphTraversalService(lifecycle, factory(
                    temp.resolve("rebuild-race"))).traverse(query(workspace, documentSeed,
                    snapshotA, EnumSet.of(GraphRelationType.CONTAINS)));
            chunk(document, "B");
            lifecycle.rebuild(assembler.assemble(workspace));

            assertThatThrownBy(() -> admission(lifecycle).admit(
                    GraphEvidenceAdmissionRequest.of(new EvidenceWorkspace(workspace.id(),
                            "rebuild-race"), traversal)))
                    .isInstanceOf(GraphProjectionException.class)
                    .extracting(failure -> ((GraphProjectionException) failure).failureType())
                    .isEqualTo(GraphProjectionFailureType.PROJECTION_STALE);
            GraphProjectionSnapshot snapshotB = lifecycle.readiness(workspace)
                    .controlPlane().appliedSnapshot();
            assertThat(snapshotB.generation()).isGreaterThan(snapshotA.generation());
        }
    }

    @Test
    void admissionIsDeterministicAcrossBackendRestart() {
        GraphWorkspaceScope workspace = workspace("restart");
        long document = document(workspace, "restart.txt");
        chunk(document, "restart evidence");
        GraphProjectionInput input = assembler.assemble(workspace);
        GraphEntityIdentity documentSeed = input.entities().stream()
                .filter(entity -> entity.identity().type() == GraphEntityType.SOURCE_DOCUMENT)
                .findFirst().orElseThrow().identity();
        Path backendPath = temp.resolve("restart");
        GraphTraversalQuery query;

        GraphEvidenceAdmissionResult beforeRestart;
        try (var lifecycle = lifecycle(factory(backendPath))) {
            GraphProjectionSnapshot snapshot = lifecycle.rebuild(input)
                    .controlPlane().appliedSnapshot();
            query = query(workspace, documentSeed, snapshot,
                    EnumSet.of(GraphRelationType.CONTAINS));
            beforeRestart = admission(lifecycle).admit(GraphEvidenceAdmissionRequest.of(
                    new EvidenceWorkspace(workspace.id(), "restart"),
                    new GraphTraversalService(lifecycle, factory(backendPath)).traverse(query)));
        }

        try (var restarted = lifecycle(factory(backendPath))) {
            GraphEvidenceAdmissionResult afterRestart = admission(restarted).admit(
                    GraphEvidenceAdmissionRequest.of(new EvidenceWorkspace(workspace.id(),
                            "restart"), new GraphTraversalService(restarted, factory(backendPath))
                            .traverse(query)));
            assertThat(afterRestart).isEqualTo(beforeRestart);
        }
    }

    @Test
    void disabledGraphCapabilityIsTypedAndLeavesTheLexicalBaselineUntouched() throws Exception {
        GraphWorkspaceScope workspace = workspace("disabled-baseline");
        wiki(workspace, "wiki-baseline", "Baseline Page", List.of(), List.of(), "基線內容");
        GraphProjectionSnapshot snapshot = GraphProjectionSnapshot.fromProof(workspace, VERSION,
                1, WikiContentHash.sha256("unused"));
        GraphTraversalResult traversal = new GraphTraversalResult(snapshot, List.of(), 1, 0,
                java.util.Set.of());

        assertThatThrownBy(() -> disabledContextAdmission.admit(
                GraphEvidenceAdmissionRequest.of(new EvidenceWorkspace(workspace.id(),
                        "disabled-baseline"), traversal)))
                .isInstanceOf(GraphProjectionException.class)
                .extracting(failure -> ((GraphProjectionException) failure).failureType())
                .isEqualTo(GraphProjectionFailureType.CAPABILITY_DISABLED);

        EvidenceBundle baseline = retrievalService.retrieve(
                RetrievalRequest.defaults("Baseline Page",
                        org.km.llmwiki.rag.RetrievalMode.WIKI_ONLY));
        assertThat(baseline.workspace().id()).isEqualTo(workspace.id());
        assertThat(baseline.insufficientEvidence()).isTrue();
    }

    private GraphEvidenceAdmissionService admission(GraphProjectionLifecycleService lifecycle) {
        return new GraphEvidenceAdmissionService(lifecycle, publishedWikiRepository,
                publishedWikiContentReader, sourceAuthorityRepository);
    }

    private GraphWorkspaceScope workspace(String name) {
        return new GraphWorkspaceScope(workspaces.create(new CreateWorkspaceRequest(name,
                temp.resolve(name).toString())).id());
    }

    private long document(GraphWorkspaceScope workspace, String name) {
        long id = documents.insert(workspace.id(), name, name, "txt", "inbox/" + name,
                WikiContentHash.sha256("original-" + name), 8L, "text/plain",
                "2026-09-08T00:00:00Z", "PROCESSED", null, null);
        documents.markExtractionSucceeded(id, WikiContentHash.sha256("content-" + name));
        return id;
    }

    private void chunk(long document, String content) {
        chunks.deleteByDocumentId(document);
        db().sql("""
                INSERT INTO source_chunk(document_id, chunk_no, content, normalized_content,
                    content_hash, created_at, updated_at) VALUES(?, 1, ?, ?, ?,
                    '2026-09-08T00:00:00Z', '2026-09-08T00:00:00Z')
                """).params(document, content, content, WikiContentHash.sha256(content)).update();
    }

    private void wiki(GraphWorkspaceScope workspace, String knowledgeId, String title,
                      List<String> tags, List<Long> sources, String body) throws Exception {
        String content = new StringBuilder("---\n")
                .append("id: \"").append(knowledgeId).append("\"\n")
                .append("title: \"").append(title).append("\"\n")
                .append("type: \"CONCEPT\"\nstatus: \"PUBLISHED\"\n")
                .append(renderList("aliases", List.of()))
                .append(renderList("tags", tags))
                .append(renderList("sources", sources.stream()
                        .map(id -> "document:" + id).toList()))
                .append("created_at: \"2026-09-08T00:00:00Z\"\n")
                .append("updated_at: \"2026-09-08T00:00:00Z\"\n")
                .append("---\n\n# ").append(title).append('\n').append(body).toString();
        String logicalPath = paths.resolveLogicalPath(WikiPageType.CONCEPT, title);
        Path target = Path.of(workspaces.get(workspace.id()).vaultPath())
                .resolve(logicalPath.substring("vault/".length()));
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        db().sql("""
                INSERT INTO knowledge_page(workspace_id, knowledge_id, title, normalized_title,
                    type, markdown_path, status, content_hash, revision, created_at, updated_at)
                VALUES(?, ?, ?, ?, 'CONCEPT', ?, 'PUBLISHED', ?, 1,
                    '2026-09-08T00:00:00Z', '2026-09-08T00:00:00Z')
                """).params(workspace.id(), knowledgeId, title,
                org.km.llmwiki.wiki.WikiTargetReference.normalizeTitle(title), logicalPath,
                WikiContentHash.sha256(content)).update();
    }

    private static String renderList(String field, List<String> values) {
        if (values.isEmpty()) return field + ": []\n";
        StringBuilder result = new StringBuilder(field).append(":\n");
        values.forEach(value -> result.append("  - \"")
                .append(value.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n"))
                .append("\"\n"));
        return result.toString();
    }

    private static GraphTraversalQuery query(GraphWorkspaceScope workspace,
                                             GraphEntityIdentity seed,
                                             GraphProjectionSnapshot snapshot,
                                             EnumSet<GraphRelationType> relationTypes) {
        return new GraphTraversalQuery(workspace, List.of(seed), relationTypes,
                new org.km.llmwiki.graph.GraphTraversalBounds(2, 8, 16, 32, 32, 16),
                GraphTraversalOrdering.DEPTH_SEED_ENTITY_PATH_V1, snapshot);
    }

    private ArcadeDbGraphProjectionBackendFactory factory(Path path) {
        return new ArcadeDbGraphProjectionBackendFactory(path, VERSION);
    }

    private GraphProjectionLifecycleService lifecycle(GraphProjectionBackendFactory factory) {
        return new GraphProjectionLifecycleService(true,
                ArcadeDbGraphProjectionBackendFactory.PROVIDER, VERSION, repository, factory,
                currentness);
    }
}
