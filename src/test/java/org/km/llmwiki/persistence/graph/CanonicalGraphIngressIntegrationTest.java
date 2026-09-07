package org.km.llmwiki.persistence.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.*;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class CanonicalGraphIngressIntegrationTest extends IsolatedIntegrationTest {
    @TempDir Path temp;
    @Autowired GraphProjectionInputAssembler assembler;
    @Autowired GraphCanonicalCurrentness currentness;
    @Autowired GraphProjectionLifecycleRepository repository;
    @Autowired WorkspaceService workspaces;
    @Autowired DocumentRepository documents;
    @Autowired SourceChunkRepository chunks;
    @Autowired WikiPathContract paths;

    @Test
    void reextractionChangesPhysicalChunkIdButPreservesLogicalGraph() {
        var scope = workspace("deterministic");
        long document = document(scope);
        chunk(document, "第一段 canonical content");
        var first = assembler.assemble(scope);
        long oldChunk = chunks.findByDocumentId(document).getFirst().id();
        chunk(document, "第一段 canonical content");
        assertThat(chunks.findByDocumentId(document).getFirst().id()).isNotEqualTo(oldChunk);
        assertThat(assembler.assemble(scope)).isEqualTo(first);
        assertThat(first.entities()).extracting(e -> e.identity().type())
                .containsExactlyInAnyOrder(GraphEntityType.SOURCE_DOCUMENT, GraphEntityType.SOURCE_CHUNK);
        assertThat(first.relations()).extracting(GraphRelation::type).containsExactly(GraphRelationType.CONTAINS);
        chunk(document, "更新後的 canonical content");
        var changed = assembler.assemble(scope);
        assertThat(changed.entities()).extracting(GraphEntity::identity)
                .containsExactlyElementsOf(first.entities().stream().map(GraphEntity::identity).toList());
        assertThat(changed.sourceFingerprint()).isNotEqualTo(first.sourceFingerprint());
    }

    @Test
    void productionBackendRequiresAllThreeProofsAfterDeleteAndRestart() {
        var a = workspace("a");
        long doc = document(a);
        chunk(doc, "內容 A");
        var b = workspace("b");
        long other = document(b);
        chunk(other, "內容 B");
        Path backend = temp.resolve("graph");
        try (var lifecycle = lifecycle(backend)) {
            var ingress = new GraphProjectionIngressService(assembler, lifecycle);
            assertThat(ingress.rebuild(a.id()).ready()).isTrue();
            assertThat(ingress.rebuild(b.id()).ready()).isTrue();
            db().sql("UPDATE document SET status='DELETED' WHERE id=?").param(doc).update();
            assertThat(lifecycle.readiness(a).status()).isEqualTo(GraphProjectionVerificationStatus.STALE);
            assertThat(lifecycle.readiness(b).ready()).isTrue();
        }
        try (var restarted = lifecycle(backend)) {
            assertThat(restarted.readiness(a).ready()).isFalse();
            assertThat(restarted.readiness(b).ready()).isTrue();
            assertThat(new GraphProjectionIngressService(assembler, restarted).repair(a.id()).ready()).isTrue();
            assertThat(assembler.assemble(a).entities()).isEmpty();
        }
    }

    @Test
    void assembledAThenCanonicalBMustNotCommitReady() {
        var scope = workspace("race");
        long doc = document(scope);
        chunk(doc, "A");
        var stale = assembler.assemble(scope);
        chunk(doc, "B");
        try (var lifecycle = lifecycle(temp.resolve("race-graph"))) {
            assertThatThrownBy(() -> lifecycle.rebuild(stale)).isInstanceOfSatisfying(
                    GraphProjectionException.class, failure -> assertThat(failure.failureType())
                            .isEqualTo(GraphProjectionFailureType.PROJECTION_STALE));
            assertThat(repository.find(scope).orElseThrow().status()).isNotEqualTo(GraphProjectionReadinessStatus.READY);
            assertThat(new GraphProjectionIngressService(assembler, lifecycle).repair(scope.id()).ready()).isTrue();
        }
    }

    @Test
    void canonicalChangeDuringBackendWorkIsRejectedAtCompletion() {
        var scope = workspace("during-rebuild");
        long doc = document(scope);
        chunk(doc, "A");
        var factory = new ArcadeDbGraphProjectionBackendFactory(temp.resolve("during-graph"),
                GraphProjectionVersion.initial());
        GraphProjectionBackendFactory changing = intercept(factory, () -> chunk(doc, "B"));
        try (var lifecycle = new GraphProjectionLifecycleService(true, "arcadedb",
                GraphProjectionVersion.initial(), repository, changing, currentness)) {
            assertThatThrownBy(() -> lifecycle.rebuild(assembler.assemble(scope)))
                    .isInstanceOf(GraphProjectionException.class);
            assertThat(lifecycle.readiness(scope).ready()).isFalse();
        }
    }

    @Test
    void eligibilityChangeAndBackendUnavailableDoNotBlockCanonicalMutation() {
        var scope = workspace("eligibility");
        long doc = document(scope);
        chunk(doc, "A");
        Path path = temp.resolve("eligibility-graph");
        try (var lifecycle = lifecycle(path)) {
            assertThat(lifecycle.rebuild(assembler.assemble(scope)).ready()).isTrue();
            documents.markExtractionFailed(doc, org.km.llmwiki.source.DocumentStatus.FAILED, "FAIL", "失敗");
            assertThat(lifecycle.readiness(scope).status()).isEqualTo(GraphProjectionVerificationStatus.STALE);
        }
        var disabled = new GraphProjectionLifecycleService(false, "arcadedb",
                GraphProjectionVersion.initial(), repository, null, currentness);
        chunk(doc, "C");
        documents.markExtractionSucceeded(doc, WikiContentHash.sha256("C"));
        assertThat(new GraphProjectionIngressService(assembler, disabled).rebuild(scope.id()).status())
                .isEqualTo(GraphProjectionVerificationStatus.DISABLED);
        assertThat(chunks.findByDocumentId(doc)).hasSize(1);
    }

    @Test
    void publishedWikiRenameRepublishAndRawFileDrift() throws Exception {
        var scope = workspace("wiki");
        wiki(scope, "First", "內容", 1);
        var first = assembler.assemble(scope);
        wiki(scope, "Renamed", "新版內容", 2);
        var renamed = assembler.assemble(scope);
        assertThat(renamed.entities().getFirst().identity()).isEqualTo(first.entities().getFirst().identity());
        assertThat(renamed.sourceFingerprint()).isNotEqualTo(first.sourceFingerprint());
        try (var lifecycle = lifecycle(temp.resolve("wiki-graph"))) {
            assertThat(lifecycle.rebuild(renamed).ready()).isTrue();
            // 模擬外部 editor 修改，只操作本測試的暫存 canonical fixture。
            Files.writeString(wikiPath(scope, "Renamed"), "與 metadata 不一致");
            assertThat(lifecycle.readiness(scope).ready()).isFalse();
        }
    }

    @Test
    void malformedOrOversizedAuthorityFailsClosedWithSafeDiagnostics() throws Exception {
        var scope = workspace("invalid");
        wiki(scope, "Large", "x".repeat(CanonicalGraphProjectionInputAssembler.MAX_CONTENT_BYTES), 1);
        assertThatThrownBy(() -> assembler.assemble(scope)).isInstanceOfSatisfying(
                GraphProjectionException.class, failure -> {
                    assertThat(failure.getMessage()).doesNotContain(temp.toString()).doesNotContain("xxxx");
                });
        db().sql("UPDATE knowledge_page SET status='DRAFT' WHERE workspace_id=?").param(scope.id()).update();
        assertThat(assembler.assemble(scope).entities()).isEmpty();
        long doc = document(scope);
        chunk(doc, "valid");
        db().sql("UPDATE source_chunk SET content_hash=? WHERE document_id=?")
                .params("0".repeat(64), doc).update();
        assertThat(assembler.assemble(scope).entities()).extracting(e -> e.identity().type())
                .containsExactly(GraphEntityType.SOURCE_DOCUMENT);
        assertThat(assembler.assemble(scope).relations()).isEmpty();
    }

    @Test
    void writerReservationAndCompletionShareTransactionAndRejectStaleFingerprint() {
        var scope = workspace("guard");
        long doc = document(scope);
        chunk(doc, "A");
        var input = assembler.assemble(scope);
        AtomicBoolean callback = new AtomicBoolean();
        assertThat(currentness.withCurrent(scope, input.sourceFingerprint(), () -> {
            callback.set(true);
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isTrue();
            return "current";
        })).isEqualTo("current");
        assertThat(callback).isTrue();
        chunk(doc, "B");
        callback.set(false);
        assertThatThrownBy(() -> currentness.withCurrent(scope, input.sourceFingerprint(), () -> {
            callback.set(true);
            return true;
        })).isInstanceOf(GraphProjectionException.class);
        assertThat(callback).isFalse();
    }

    @Test
    void olderRebuildCompletionCannotReplaceNewerCanonicalReady() {
        var scope = workspace("newer-wins");
        long doc = document(scope);
        chunk(doc, "A");
        var inputA = assembler.assemble(scope);
        var factory = new ArcadeDbGraphProjectionBackendFactory(temp.resolve("newer-graph"),
                GraphProjectionVersion.initial());
        try (var newer = new GraphProjectionLifecycleService(true, "arcadedb",
                GraphProjectionVersion.initial(), repository, factory, currentness)) {
            GraphCanonicalCurrentness changing = new GraphCanonicalCurrentness() {
                public <T> T withCurrent(GraphWorkspaceScope workspace, String fingerprint,
                                         java.util.function.Supplier<T> action) {
                    chunk(doc, "B");
                    assertThat(newer.rebuild(assembler.assemble(scope)).ready()).isTrue();
                    return currentness.withCurrent(workspace, fingerprint, action);
                }
            };
            try (var older = new GraphProjectionLifecycleService(true, "arcadedb",
                    GraphProjectionVersion.initial(), repository, factory, changing)) {
                assertThatThrownBy(() -> older.rebuild(inputA)).isInstanceOf(GraphProjectionException.class);
                assertThat(newer.readiness(scope).ready()).isTrue();
                assertThat(repository.find(scope).orElseThrow().appliedSnapshot().sourceFingerprint())
                        .isEqualTo(assembler.assemble(scope).sourceFingerprint());
            }
        }
    }

    @Test
    void olderInvalidationCannotDegradeNewerReady() {
        var scope = workspace("invalidation");
        long doc = document(scope);
        chunk(doc, "A");
        var factory = new ArcadeDbGraphProjectionBackendFactory(temp.resolve("invalidation-graph"),
                GraphProjectionVersion.initial());
        try (var newer = new GraphProjectionLifecycleService(true, "arcadedb",
                GraphProjectionVersion.initial(), repository, factory, currentness)) {
            newer.rebuild(assembler.assemble(scope));
            AtomicBoolean once = new AtomicBoolean(true);
            GraphCanonicalCurrentness racing = new GraphCanonicalCurrentness() {
                public <T> T withCurrent(GraphWorkspaceScope workspace, String fingerprint,
                                         java.util.function.Supplier<T> action) {
                    if (once.getAndSet(false)) {
                        chunk(doc, "B");
                        newer.rebuild(assembler.assemble(scope));
                    }
                    return currentness.withCurrent(workspace, fingerprint, action);
                }
            };
            try (var older = new GraphProjectionLifecycleService(true, "arcadedb",
                    GraphProjectionVersion.initial(), repository, factory, racing)) {
                assertThat(older.readiness(scope).ready()).isTrue();
                assertThat(newer.readiness(scope).ready()).isTrue();
            }
        }
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void ambientTransactionCannotReuseAnOlderReadSnapshot() {
        var scope = workspace("ambient");
        var input = assembler.assemble(scope);
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThatThrownBy(() -> currentness.withCurrent(scope, input.sourceFingerprint(), () -> true))
                    .isInstanceOfSatisfying(GraphProjectionException.class, failure ->
                            assertThat(failure.failureType()).isEqualTo(GraphProjectionFailureType.TRANSACTION_FAILURE));
        });
    }

    @Test
    void archiveHashAndPathAreValidatedWithoutLeakingContent() throws Exception {
        var scope = workspace("archive");
        long doc = document(scope);
        Path archive = Path.of(workspaces.get(scope.id()).archivePath());
        Files.writeString(archive.resolve("source.txt"), "original");
        db().sql("UPDATE document SET archive_path='archive/source.txt' WHERE id=?").param(doc).update();
        assertThat(assembler.assemble(scope).entities()).hasSize(1);
        Files.writeString(archive.resolve("source.txt"), "changed");
        assertThatThrownBy(() -> assembler.assemble(scope)).isInstanceOf(GraphProjectionException.class);
        db().sql("UPDATE document SET archive_path='../source.txt' WHERE id=?").param(doc).update();
        assertThatThrownBy(() -> assembler.assemble(scope)).isInstanceOfSatisfying(GraphProjectionException.class,
                failure -> assertThat(failure.getMessage()).doesNotContain(temp.toString()));
    }

    @Test
    void oversizedMetadataAndUnknownWorkspaceFailClosed() {
        var scope = workspace("metadata");
        long doc = document(scope);
        chunk(doc, "valid");
        db().sql("UPDATE source_chunk SET heading_path=? WHERE document_id=?")
                .params("x".repeat(CanonicalGraphProjectionInputAssembler.MAX_CONTENT_BYTES + 1), doc).update();
        assertThatThrownBy(() -> assembler.assemble(scope)).isInstanceOfSatisfying(GraphProjectionException.class,
                failure -> assertThat(failure.failureType()).isEqualTo(GraphProjectionFailureType.INVALID_PROJECTION_INPUT));
        assertThatThrownBy(() -> assembler.assemble(new GraphWorkspaceScope(Integer.MAX_VALUE)))
                .isInstanceOf(GraphProjectionException.class);
    }

    @Test
    void pendingPublicationBlocksReadyAndRestartReconciliation() {
        var scope = workspace("publishing");
        long doc = document(scope);
        chunk(doc, "A");
        var input = assembler.assemble(scope);
        Path backend = temp.resolve("publishing-graph");
        try (var lifecycle = lifecycle(backend)) {
            lifecycle.rebuild(input);
            long operationId = pendingPublish(scope, doc);
            for (String status : List.of("PREPARED", "FILE_COMMITTED", "RECONCILIATION_REQUIRED")) {
                db().sql("UPDATE wiki_publish_operation SET status=?, failure_detail=? WHERE id=?")
                        .params(status, status.equals("RECONCILIATION_REQUIRED") ? "測試未完成發布" : null, operationId).update();
                assertThatThrownBy(() -> currentness.withCurrent(scope, input.sourceFingerprint(), () -> true))
                        .isInstanceOfSatisfying(GraphProjectionException.class, failure ->
                                assertThat(failure.failureType()).isEqualTo(GraphProjectionFailureType.PROJECTION_STALE));
            }
            assertThat(lifecycle.readiness(scope).status()).isEqualTo(GraphProjectionVerificationStatus.STALE);
        }
        // 模擬 backend 已提交、SQLite 尚未 READY 時重啟；不能只根據兩方 proof 恢復 READY。
        var operation = repository.reserve(scope, "arcadedb", GraphProjectionVersion.initial(),
                GraphProjectionOperationKind.REBUILD, input.sourceFingerprint(), "restart-owner");
        try (var factory = new ArcadeDbGraphProjectionBackendFactory(backend, GraphProjectionVersion.initial());
             var target = factory.openForWrite(scope)) {
            target.rebuild(input, operation.targetSnapshot());
        }
        try (var restarted = lifecycle(backend)) {
            restarted.reconcileInterruptedOperations();
            assertThat(restarted.readiness(scope).ready()).isFalse();
        }
    }

    @Autowired javax.sql.DataSource dataSource;

    @Test
    void writerReservationPreventsPublicationPreparationUntilReadyDecisionCommits() throws Exception {
        var scope = workspace("writer-order");
        long doc = document(scope);
        long operation = pendingPublish(scope, doc);
        db().sql("UPDATE wiki_publish_operation SET status='ROLLED_BACK', failure_detail='fixture' WHERE id=?")
                .param(operation).update();
        var input = assembler.assemble(scope);
        try (var independent = dataSource.getConnection(); var statement = independent.createStatement()) {
            statement.execute("PRAGMA busy_timeout=0");
            String prepare = "UPDATE wiki_publish_operation SET status='PREPARED', failure_detail=NULL WHERE id=" + operation;
            try {
                assertThat(currentness.withCurrent(scope, input.sourceFingerprint(), () -> {
                    assertThatThrownBy(() -> statement.executeUpdate(prepare))
                            .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("SQLITE_BUSY");
                    return true;
                })).isTrue();
                assertThat(statement.executeUpdate(prepare)).isEqualTo(1);
            } finally {
                statement.execute("PRAGMA busy_timeout=5000");
            }
        }
        assertThatThrownBy(() -> assembler.assemble(scope)).isInstanceOf(GraphProjectionException.class);
    }

    @Test
    void restartDetectsUnobservedCanonicalChangeAndRepairUsesFreshInput() {
        var scope = workspace("unobserved");
        long doc = document(scope);
        chunk(doc, "A");
        Path path = temp.resolve("unobserved-graph");
        try (var lifecycle = lifecycle(path)) {
            lifecycle.rebuild(assembler.assemble(scope));
        }
        chunk(doc, "B");
        assertThat(repository.find(scope).orElseThrow().status()).isEqualTo(GraphProjectionReadinessStatus.READY);
        try (var restarted = lifecycle(path)) {
            assertThat(restarted.readiness(scope).status()).isEqualTo(GraphProjectionVerificationStatus.STALE);
            assertThat(new GraphProjectionIngressService(assembler, restarted).repair(scope.id()).ready()).isTrue();
        }
    }

    @Test
    void unavailableAndUnconfiguredBackendsDoNotParticipateInCanonicalWrites() {
        var scope = workspace("unavailable");
        long doc = document(scope);
        chunk(doc, "A");
        Path path = temp.resolve("unavailable-graph");
        try (var lifecycle = lifecycle(path)) {
            lifecycle.rebuild(assembler.assemble(scope));
        }
        var unavailable = org.mockito.Mockito.mock(GraphProjectionBackendFactory.class);
        org.mockito.Mockito.when(unavailable.provider()).thenReturn("arcadedb");
        org.mockito.Mockito.when(unavailable.projectionVersion()).thenReturn(GraphProjectionVersion.initial());
        org.mockito.Mockito.when(unavailable.openExisting(scope)).thenThrow(
                new GraphProjectionException(GraphProjectionFailureType.BACKEND_LOCKED));
        chunk(doc, "B");
        try (var lifecycle = new GraphProjectionLifecycleService(true, "arcadedb",
                GraphProjectionVersion.initial(), repository, unavailable, currentness)) {
            assertThat(lifecycle.readiness(scope).status()).isEqualTo(GraphProjectionVerificationStatus.BACKEND_UNAVAILABLE);
        }
        try (var lifecycle = new GraphProjectionLifecycleService(true, "missing",
                GraphProjectionVersion.initial(), repository, null, currentness)) {
            var ingress = new GraphProjectionIngressService(assembler, lifecycle);
            assertThat(ingress.rebuild(scope.id()).status()).isEqualTo(GraphProjectionVerificationStatus.NOT_CONFIGURED);
            assertThat(ingress.repair(scope.id()).status()).isEqualTo(GraphProjectionVerificationStatus.NOT_CONFIGURED);
        }
        assertThat(chunks.findByDocumentId(doc).getFirst().normalizedContent()).isEqualTo("B");
    }

    private long insert(String sql, Object... params) {
        return db().sql(sql + " RETURNING id").params(params).query(Long.class).single();
    }

    private long pendingPublish(GraphWorkspaceScope scope, long doc) {
        long job = insert("INSERT INTO processing_job(workspace_id,job_id,job_type,created_at,updated_at) VALUES(?, 'graph-fixture', 'ANALYZE','now','now')", scope.id());
        long item = insert("INSERT INTO processing_job_item(job_id,document_id) VALUES(?,?)", job, doc);
        long analysis = insert("""
                INSERT INTO document_analysis(job_item_id,document_id,status,prompt_identifier,prompt_version,
                    provider,model,contract_version,created_at,updated_at)
                VALUES(?,?,'SUCCEEDED','prompt','v1','provider','model','v1','now','now')
                """, item, doc);
        long candidate = insert("""
                INSERT INTO knowledge_candidate(document_analysis_id,document_id,candidate_no,title,
                    candidate_type,summary,confidence,rationale,created_at,updated_at)
                VALUES(?,?,1,'Title','CONCEPT','summary',0.9,'reason','now','now')
                """, analysis, doc);
        long proposal = insert("""
                INSERT INTO knowledge_proposal(workspace_id,document_analysis_id,document_id,knowledge_candidate_id,
                    action,status,provider,model,prompt_identifier,prompt_version,contract_version,
                    normalized_data_json,created_at,updated_at)
                VALUES(?,?,?,?,'CREATE','APPROVED','provider','model','prompt','v1','v1','{}','now','now')
                """, scope.id(), analysis, doc, candidate);
        long draft = insert("""
                INSERT INTO wiki_draft(workspace_id,proposal_id,action,page_type,title,target_title,target_page_type,
                    target_path,status,base_content_hash,rendered_content_hash,input_hash,structured_draft_json,
                    base_content,rendered_content,created_at,updated_at)
                VALUES(?,?,'CREATE','CONCEPT','Title','Title','CONCEPT','vault/concepts/title.md','READY',
                    'hash','hash','hash','{}','','','now','now')
                """, scope.id(), proposal);
        return insert("""
                INSERT INTO wiki_publish_operation(workspace_id,draft_id,proposal_id,action,knowledge_id,
                    target_path,content_hash,revision,status,created_at,updated_at)
                VALUES(?,?,?,'CREATE','pending','vault/concepts/title.md','hash',1,'PREPARED','now','now')
                """, scope.id(), draft, proposal);
    }

    private GraphWorkspaceScope workspace(String name) {
        return new GraphWorkspaceScope(workspaces.create(new CreateWorkspaceRequest(name,
                temp.resolve(name).toString())).id());
    }

    private long document(GraphWorkspaceScope scope) {
        long id = documents.insert(scope.id(), "source.txt", "source.txt", "txt", "inbox/source.txt",
                WikiContentHash.sha256("original"), 8L, "text/plain", "2026-09-07T00:00:00Z",
                "PROCESSED", null, null);
        documents.markExtractionSucceeded(id, WikiContentHash.sha256("content"));
        return id;
    }

    private void chunk(long doc, String content) {
        chunks.deleteByDocumentId(doc);
        db().sql("""
                INSERT INTO source_chunk(document_id, chunk_no, content, normalized_content,
                    content_hash, created_at, updated_at) VALUES(?, 1, ?, ?, ?,
                    '2026-09-07T00:00:00Z', '2026-09-07T00:00:00Z')
                """).params(doc, content, content, WikiContentHash.sha256(content)).update();
    }

    private Path wikiPath(GraphWorkspaceScope scope, String title) {
        return Path.of(workspaces.get(scope.id()).vaultPath())
                .resolve(paths.resolveLogicalPath(WikiPageType.CONCEPT, title).substring("vault/".length()));
    }

    private void wiki(GraphWorkspaceScope scope, String title, String body, int revision) throws Exception {
        String content = "---\nid: \"wiki-1\"\ntitle: \"" + title
                + "\"\ntype: \"CONCEPT\"\nstatus: \"PUBLISHED\"\n---\n\n# " + title + "\n" + body;
        Path target = wikiPath(scope, title);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        db().sql("""
                INSERT INTO knowledge_page(workspace_id, knowledge_id, title, normalized_title,
                    type, markdown_path, status, content_hash, revision, created_at, updated_at)
                VALUES(?, 'wiki-1', ?, ?, 'CONCEPT', ?, 'PUBLISHED', ?, ?, '2026-09-07T00:00:00Z', '2026-09-07T00:00:00Z')
                ON CONFLICT(workspace_id, knowledge_id) DO UPDATE SET title=excluded.title,
                    normalized_title=excluded.normalized_title, markdown_path=excluded.markdown_path,
                    content_hash=excluded.content_hash, revision=excluded.revision
                """).params(scope.id(), title, title.toLowerCase(),
                paths.resolveLogicalPath(WikiPageType.CONCEPT, title), WikiContentHash.sha256(content), revision).update();
    }

    private GraphProjectionLifecycleService lifecycle(Path path) {
        return new GraphProjectionLifecycleService(true, "arcadedb", GraphProjectionVersion.initial(),
                repository, new ArcadeDbGraphProjectionBackendFactory(path, GraphProjectionVersion.initial()),
                currentness);
    }

    private static GraphProjectionBackendFactory intercept(GraphProjectionBackendFactory delegate, Runnable changed) {
        return new GraphProjectionBackendFactory() {
            public String provider() { return delegate.provider(); }
            public GraphProjectionVersion projectionVersion() { return delegate.projectionVersion(); }
            public Optional<GraphProjectionBackend> openExisting(GraphWorkspaceScope workspace) {
                return delegate.openExisting(workspace);
            }
            public GraphProjectionBackend openForWrite(GraphWorkspaceScope workspace) {
                GraphProjectionBackend backend = delegate.openForWrite(workspace);
                return new GraphProjectionBackend() {
                    public GraphProjectionSnapshot rebuild(GraphProjectionInput input, GraphProjectionSnapshot target) {
                        var result = backend.rebuild(input, target);
                        changed.run();
                        return result;
                    }
                    public GraphProjectionBackendProof readProof(GraphWorkspaceScope scope) { return backend.readProof(scope); }
                    public GraphProjectionWriteResult clearWorkspace(GraphWorkspaceScope scope, GraphProjectionSnapshot expected) {
                        return backend.clearWorkspace(scope, expected);
                    }
                    public void close() { backend.close(); }
                };
            }
            public void close() { delegate.close(); }
        };
    }
}
