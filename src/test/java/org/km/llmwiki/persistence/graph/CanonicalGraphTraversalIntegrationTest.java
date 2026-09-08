package org.km.llmwiki.persistence.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.GraphEntity;
import org.km.llmwiki.graph.GraphEntityIdentity;
import org.km.llmwiki.graph.GraphEntityType;
import org.km.llmwiki.graph.GraphCanonicalCurrentness;
import org.km.llmwiki.graph.GraphProjectionBackendFactory;
import org.km.llmwiki.graph.GraphProjectionBackendProof;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionInputAssembler;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphProjectionLifecycleService;
import org.km.llmwiki.graph.GraphProjectionSnapshot;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.graph.GraphRelation;
import org.km.llmwiki.graph.GraphRelationType;
import org.km.llmwiki.graph.GraphTraversalBounds;
import org.km.llmwiki.graph.GraphTraversalBackend;
import org.km.llmwiki.graph.GraphTraversalBackendFactory;
import org.km.llmwiki.graph.GraphTraversalOrdering;
import org.km.llmwiki.graph.GraphTraversalQuery;
import org.km.llmwiki.graph.GraphTraversalResult;
import org.km.llmwiki.graph.GraphTraversalService;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.persistence.graph.arcadedb.ArcadeDbGraphProjectionBackendFactory;
import org.km.llmwiki.source.DocumentRepository;
import org.km.llmwiki.source.SourceChunkRepository;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End-to-end evidence for canonical currentness around production Graph traversal. */
class CanonicalGraphTraversalIntegrationTest extends IsolatedIntegrationTest {

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

    @Test
    void canonicalContainsTraversalIsDeterministicAcrossRestartAndWorkspaceScoped() {
        GraphWorkspaceScope firstWorkspace = workspace("first");
        long firstDocument = document(firstWorkspace, "first.txt");
        chunk(firstDocument, "第一個 workspace 的內容");
        GraphProjectionInput firstInput = assembler.assemble(firstWorkspace);
        GraphEntityIdentity firstSeed = entity(firstInput, GraphEntityType.SOURCE_DOCUMENT).identity();

        GraphWorkspaceScope secondWorkspace = workspace("second");
        long secondDocument = document(secondWorkspace, "second.txt");
        chunk(secondDocument, "第二個 workspace 的內容");
        GraphProjectionInput secondInput = assembler.assemble(secondWorkspace);
        Path backendPath = temp.resolve("restart-and-scope");

        GraphTraversalQuery firstQuery;
        GraphTraversalResult beforeRestart;
        var firstFactory = factory(backendPath);
        try (var lifecycle = lifecycle(firstFactory)) {
            GraphProjectionSnapshot firstSnapshot = lifecycle.rebuild(firstInput)
                    .controlPlane().appliedSnapshot();
            lifecycle.rebuild(secondInput);
            firstQuery = query(firstWorkspace, firstSeed, firstSnapshot);
            beforeRestart = new GraphTraversalService(lifecycle, firstFactory).traverse(firstQuery);

            assertThat(beforeRestart.candidates()).hasSize(1);
            assertThat(beforeRestart.candidates().getFirst().entity().identity().type())
                    .isEqualTo(GraphEntityType.SOURCE_CHUNK);
            assertThat(beforeRestart.candidates().getFirst().path())
                    .extracting(relation -> relation.type())
                    .containsExactly(GraphRelationType.CONTAINS);
            assertThat(beforeRestart.candidates())
                    .allMatch(candidate -> candidate.entity().identity().workspace()
                            .equals(firstWorkspace));
        }

        var restartedFactory = factory(backendPath);
        try (var restarted = lifecycle(restartedFactory)) {
            GraphTraversalResult afterRestart = new GraphTraversalService(restarted,
                    restartedFactory).traverse(firstQuery);
            assertThat(afterRestart).isEqualTo(beforeRestart);
        }
    }

    @Test
    void canonicalAdmittedRelationsTraverseDeterministicallyAcrossRestart() throws Exception {
        GraphWorkspaceScope workspace = workspace("relation-restart");
        long document = document(workspace, "source.txt");
        wiki(workspace, "wiki-target", "Target Page", List.of(), List.of(), "目標內容");
        wiki(workspace, "wiki-source", "Source Page", List.of("RAG"), List.of(document),
                "[[Target Page|目標頁面]]");
        GraphProjectionInput input = assembler.assemble(workspace);
        GraphEntityIdentity seed = input.entities().stream()
                .filter(entity -> entity.identity().type() == GraphEntityType.WIKI_PAGE)
                .filter(entity -> entity.provenance().authority().stableId().equals("wiki-source"))
                .findFirst().orElseThrow().identity();
        Path backendPath = temp.resolve("relation-restart-graph");
        EnumSet<GraphRelationType> allowed = EnumSet.of(GraphRelationType.LINKS_TO,
                GraphRelationType.TAGGED_WITH, GraphRelationType.DERIVED_FROM);

        GraphTraversalQuery query;
        GraphTraversalResult beforeRestart;
        var firstFactory = factory(backendPath);
        try (var lifecycle = lifecycle(firstFactory)) {
            GraphProjectionSnapshot snapshot = lifecycle.rebuild(input)
                    .controlPlane().appliedSnapshot();
            query = query(workspace, seed, snapshot, allowed);
            beforeRestart = new GraphTraversalService(lifecycle, firstFactory).traverse(query);

            assertThat(beforeRestart.candidates()).hasSize(3);
            assertThat(beforeRestart.candidates()).extracting(candidate ->
                    candidate.entity().identity().type()).containsExactlyInAnyOrder(
                    GraphEntityType.WIKI_PAGE, GraphEntityType.TAG,
                    GraphEntityType.SOURCE_DOCUMENT);
            assertThat(beforeRestart.candidates()).allSatisfy(candidate -> {
                assertThat(candidate.depth()).isEqualTo(1);
                assertThat(candidate.path()).hasSize(1);
                assertThat(candidate.path().getFirst().source()).isEqualTo(seed);
                assertThat(candidate.path().getFirst().target())
                        .isEqualTo(candidate.entity().identity());
            });
            assertThat(beforeRestart.candidates()).flatExtracting(candidate -> candidate.path())
                    .extracting(GraphRelation::type).containsExactlyInAnyOrder(
                    GraphRelationType.LINKS_TO, GraphRelationType.TAGGED_WITH,
                    GraphRelationType.DERIVED_FROM);
        }

        var restartedFactory = factory(backendPath);
        try (var restarted = lifecycle(restartedFactory)) {
            GraphTraversalResult afterRestart = new GraphTraversalService(restarted,
                    restartedFactory).traverse(query);
            assertThat(afterRestart).isEqualTo(beforeRestart);
        }
    }

    @Test
    void canonicalMutationAfterMaterializationRejectsSnapshotAAtFinalCurrentnessCheck() {
        GraphWorkspaceScope workspace = workspace("final-currentness-race");
        long document = document(workspace, "race.txt");
        chunk(document, "A");
        GraphProjectionInput inputA = assembler.assemble(workspace);
        Path backendPath = temp.resolve("final-currentness-race");
        var productionFactory = factory(backendPath);

        try (var lifecycle = lifecycle(productionFactory)) {
            GraphProjectionSnapshot snapshotA = lifecycle.rebuild(inputA)
                    .controlPlane().appliedSnapshot();
            GraphTraversalQuery queryA = query(workspace,
                    entity(inputA, GraphEntityType.SOURCE_DOCUMENT).identity(), snapshotA);
            GraphTraversalBackendFactory mutatingFactory = afterTraversal(productionFactory,
                    () -> chunk(document, "B"));

            assertFailure(GraphProjectionFailureType.PROJECTION_STALE,
                    () -> new GraphTraversalService(lifecycle, mutatingFactory).traverse(queryA));
            assertThat(assembler.assemble(workspace).sourceFingerprint())
                    .isNotEqualTo(snapshotA.sourceFingerprint());
        }
    }

    @Test
    void generationBPublishedBeforeSecondBackendProofRejectsLateSnapshotA() {
        GraphWorkspaceScope workspace = workspace("backend-proof-race");
        long document = document(workspace, "race.txt");
        chunk(document, "A");
        GraphProjectionInput inputA = assembler.assemble(workspace);
        Path backendPath = temp.resolve("backend-proof-race");
        var productionFactory = factory(backendPath);

        try (var lifecycle = lifecycle(productionFactory)) {
            GraphProjectionSnapshot snapshotA = lifecycle.rebuild(inputA)
                    .controlPlane().appliedSnapshot();
            GraphTraversalQuery queryA = query(workspace,
                    entity(inputA, GraphEntityType.SOURCE_DOCUMENT).identity(), snapshotA);
            GraphTraversalBackendFactory racingFactory = publishAfterTraversal(
                    productionFactory, workspace, () -> {
                        chunk(document, "B");
                        lifecycle.rebuild(assembler.assemble(workspace));
                    });

            assertFailure(GraphProjectionFailureType.PROJECTION_STALE,
                    () -> new GraphTraversalService(lifecycle, racingFactory).traverse(queryA));
            GraphProjectionSnapshot snapshotB = lifecycle.readiness(workspace)
                    .controlPlane().appliedSnapshot();
            assertThat(snapshotB.generation()).isGreaterThan(snapshotA.generation());
            assertThat(snapshotB.sourceFingerprint()).isNotEqualTo(snapshotA.sourceFingerprint());
        }
    }

    private GraphWorkspaceScope workspace(String name) {
        return new GraphWorkspaceScope(workspaces.create(new CreateWorkspaceRequest(name,
                temp.resolve(name).toString())).id());
    }

    private long document(GraphWorkspaceScope workspace, String name) {
        long id = documents.insert(workspace.id(), name, name, "txt", "inbox/" + name,
                WikiContentHash.sha256("original-" + name), 8L, "text/plain",
                "2026-09-07T00:00:00Z", "PROCESSED", null, null);
        documents.markExtractionSucceeded(id, WikiContentHash.sha256("content-" + name));
        return id;
    }

    private void chunk(long document, String content) {
        chunks.deleteByDocumentId(document);
        db().sql("""
                INSERT INTO source_chunk(document_id, chunk_no, content, normalized_content,
                    content_hash, created_at, updated_at) VALUES(?, 1, ?, ?, ?,
                    '2026-09-07T00:00:00Z', '2026-09-07T00:00:00Z')
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
                .append("created_at: \"2026-09-07T00:00:00Z\"\n")
                .append("updated_at: \"2026-09-07T00:00:00Z\"\n")
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
                    '2026-09-07T00:00:00Z', '2026-09-07T00:00:00Z')
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

    private static GraphEntity entity(GraphProjectionInput input, GraphEntityType type) {
        return input.entities().stream().filter(candidate -> candidate.identity().type() == type)
                .findFirst().orElseThrow();
    }

    private static GraphTraversalQuery query(GraphWorkspaceScope workspace,
                                             GraphEntityIdentity seed,
                                             GraphProjectionSnapshot snapshot) {
        return query(workspace, seed, snapshot, EnumSet.of(GraphRelationType.CONTAINS));
    }

    private static GraphTraversalQuery query(GraphWorkspaceScope workspace,
                                             GraphEntityIdentity seed,
                                             GraphProjectionSnapshot snapshot,
                                             EnumSet<GraphRelationType> relationTypes) {
        return new GraphTraversalQuery(workspace, List.of(seed), relationTypes,
                new GraphTraversalBounds(2, 8, 16, 32, 32, 16),
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

    private static GraphTraversalBackendFactory afterTraversal(
            GraphTraversalBackendFactory delegate, Runnable mutation) {
        return wrappingFactory(delegate, backend -> new ForwardingBackend(backend) {
            @Override
            public GraphTraversalResult traverse(GraphTraversalQuery query) {
                GraphTraversalResult result = super.traverse(query);
                mutation.run();
                return result;
            }
        });
    }

    private static GraphTraversalBackendFactory publishAfterTraversal(
            GraphTraversalBackendFactory delegate, GraphWorkspaceScope workspace,
            Runnable publication) {
        AtomicBoolean advanced = new AtomicBoolean();
        return wrappingFactory(delegate, backend -> new ForwardingBackend(backend) {
            @Override
            public GraphTraversalResult traverse(GraphTraversalQuery query) {
                GraphTraversalResult result = super.traverse(query);
                closeDelegate();
                publication.run();
                advanced.set(true);
                return result;
            }

            @Override
            public GraphProjectionBackendProof readProof(GraphWorkspaceScope requestedWorkspace) {
                if (!advanced.get()) {
                    return super.readProof(requestedWorkspace);
                }
                try (GraphTraversalBackend current = delegate.openTraversal(workspace)
                        .orElseThrow()) {
                    return current.readProof(requestedWorkspace);
                }
            }
        });
    }

    private static GraphTraversalBackendFactory wrappingFactory(
            GraphTraversalBackendFactory delegate,
            java.util.function.Function<GraphTraversalBackend, GraphTraversalBackend> wrapper) {
        return new GraphTraversalBackendFactory() {
            @Override public GraphProjectionVersion projectionVersion() {
                return delegate.projectionVersion();
            }
            @Override public Optional<GraphTraversalBackend> openTraversal(
                    GraphWorkspaceScope workspace) {
                return delegate.openTraversal(workspace).map(wrapper);
            }
        };
    }

    private static class ForwardingBackend implements GraphTraversalBackend {
        private GraphTraversalBackend delegate;

        ForwardingBackend(GraphTraversalBackend delegate) {
            this.delegate = delegate;
        }

        @Override
        public GraphProjectionBackendProof readProof(GraphWorkspaceScope workspace) {
            return requiredDelegate().readProof(workspace);
        }

        @Override
        public GraphTraversalResult traverse(GraphTraversalQuery query) {
            return requiredDelegate().traverse(query);
        }

        final void closeDelegate() {
            if (delegate != null) {
                delegate.close();
                delegate = null;
            }
        }

        private GraphTraversalBackend requiredDelegate() {
            if (delegate == null) {
                throw new IllegalStateException("Graph backend fixture is closed");
            }
            return delegate;
        }

        @Override
        public void close() {
            closeDelegate();
        }
    }

    private static void assertFailure(GraphProjectionFailureType expected,
                                      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(GraphProjectionException.class)
                .extracting(failure -> ((GraphProjectionException) failure).failureType())
                .isEqualTo(expected);
    }
}
