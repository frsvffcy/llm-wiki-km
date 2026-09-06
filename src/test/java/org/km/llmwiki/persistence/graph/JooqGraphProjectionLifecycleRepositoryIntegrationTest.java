package org.km.llmwiki.persistence.graph;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailure;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphProjectionOperation;
import org.km.llmwiki.graph.GraphProjectionOperationKind;
import org.km.llmwiki.graph.GraphProjectionReadinessStatus;
import org.km.llmwiki.graph.GraphProjectionSnapshot;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JooqGraphProjectionLifecycleRepositoryIntegrationTest extends IsolatedIntegrationTest {

    private static final String PROVIDER = "arcadedb";
    private static final GraphProjectionVersion VERSION = GraphProjectionVersion.initial();
    private static final String FIRST_FINGERPRINT = "a".repeat(64);
    private static final String SECOND_FINGERPRINT = "b".repeat(64);

    @Autowired
    GraphProjectionLifecycleRepository repository;

    @Autowired
    JdbcClient jdbc;

    @Test
    void generationIsMonotonicAcrossReadyClearAndRebuild() {
        GraphWorkspaceScope workspace = insertWorkspace("monotonic");
        GraphProjectionOperation first = reserve(workspace, GraphProjectionOperationKind.REBUILD,
                FIRST_FINGERPRINT, "owner-1");
        assertThat(first.generation()).isEqualTo(1);
        assertThat(repository.markReady(first, first.targetSnapshot())).isTrue();

        GraphProjectionOperation clear = reserve(workspace, GraphProjectionOperationKind.CLEAR,
                FIRST_FINGERPRINT, "owner-clear");
        assertThat(clear.generation()).isEqualTo(2);
        assertThat(repository.markCleared(clear)).isTrue();

        GraphProjectionOperation rebuilt = reserve(workspace, GraphProjectionOperationKind.REBUILD,
                SECOND_FINGERPRINT, "owner-2");
        assertThat(rebuilt.generation()).isEqualTo(3);
        assertThat(repository.markReady(rebuilt, rebuilt.targetSnapshot())).isTrue();
        assertThat(repository.find(workspace).orElseThrow()).satisfies(state -> {
            assertThat(state.status()).isEqualTo(GraphProjectionReadinessStatus.READY);
            assertThat(state.targetGeneration()).isEqualTo(3);
            assertThat(state.appliedGeneration()).isEqualTo(3);
            assertThat(state.snapshotToken()).isNotEqualTo(first.targetSnapshot().snapshotToken());
        });
    }

    @Test
    void concurrentReservationsSerializeWithDistinctDurableGenerations() throws Exception {
        GraphWorkspaceScope workspace = insertWorkspace("concurrent");
        CyclicBarrier start = new CyclicBarrier(3);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<GraphProjectionOperation> first = executor.submit(() -> {
                start.await();
                return reserve(workspace, GraphProjectionOperationKind.REBUILD,
                        FIRST_FINGERPRINT, "concurrent-1");
            });
            Future<GraphProjectionOperation> second = executor.submit(() -> {
                start.await();
                return reserve(workspace, GraphProjectionOperationKind.REPAIR,
                        SECOND_FINGERPRINT, "concurrent-2");
            });
            start.await();

            List<Long> generations = List.of(first.get(15, TimeUnit.SECONDS).generation(),
                            second.get(15, TimeUnit.SECONDS).generation()).stream()
                    .sorted().toList();
            assertThat(generations).containsExactly(1L, 2L);
            assertThat(repository.find(workspace).orElseThrow().targetGeneration()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void staleCallbacksCannotOverwriteNewerOperationOrReadyProof() {
        GraphWorkspaceScope workspace = insertWorkspace("stale-callback");
        GraphProjectionOperation first = reserve(workspace, GraphProjectionOperationKind.REBUILD,
                FIRST_FINGERPRINT, "stale-1");
        GraphProjectionOperation second = reserve(workspace, GraphProjectionOperationKind.REPAIR,
                SECOND_FINGERPRINT, "stale-2");

        assertThat(repository.markReady(first, first.targetSnapshot())).isFalse();
        assertThat(repository.markFailed(first,
                GraphProjectionFailure.of(GraphProjectionFailureType.BACKEND_FAILURE))).isFalse();
        assertThat(repository.markReady(second, second.targetSnapshot())).isTrue();
        assertThat(repository.markFailed(first,
                GraphProjectionFailure.of(GraphProjectionFailureType.TRANSACTION_FAILURE))).isFalse();

        assertThat(repository.find(workspace).orElseThrow()).satisfies(state -> {
            assertThat(state.status()).isEqualTo(GraphProjectionReadinessStatus.READY);
            assertThat(state.appliedSnapshot()).isEqualTo(second.targetSnapshot());
        });
    }

    @Test
    void providerOrProjectionVersionDriftFailsClosed() {
        GraphWorkspaceScope workspace = insertWorkspace("incompatible");
        GraphProjectionOperation first = reserve(workspace, GraphProjectionOperationKind.REBUILD,
                FIRST_FINGERPRINT, "provider-1");
        assertThat(repository.markReady(first, first.targetSnapshot())).isTrue();

        assertProjectionFailure(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE,
                () -> repository.reserve(workspace, "another-provider", VERSION,
                        GraphProjectionOperationKind.REBUILD, SECOND_FINGERPRINT, "provider-2"));
        assertProjectionFailure(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE,
                () -> repository.reserve(workspace, PROVIDER,
                        new GraphProjectionVersion("graph-projection-v2"),
                        GraphProjectionOperationKind.REBUILD, SECOND_FINGERPRINT, "version-2"));
    }

    @Test
    void staleClearCompareAndSetCannotEraseNewerOwnership() {
        GraphWorkspaceScope workspace = insertWorkspace("clear-cas");
        GraphProjectionOperation initial = reserve(workspace, GraphProjectionOperationKind.REBUILD,
                FIRST_FINGERPRINT, "clear-initial");
        repository.markReady(initial, initial.targetSnapshot());
        GraphProjectionOperation clear = reserve(workspace, GraphProjectionOperationKind.CLEAR,
                FIRST_FINGERPRINT, "clear-old");
        GraphProjectionOperation newer = reserve(workspace, GraphProjectionOperationKind.REBUILD,
                SECOND_FINGERPRINT, "clear-newer");

        assertThat(repository.markCleared(clear)).isFalse();
        assertThat(repository.markReady(newer, newer.targetSnapshot())).isTrue();
        assertThat(repository.find(workspace).orElseThrow()).satisfies(state -> {
            assertThat(state.status()).isEqualTo(GraphProjectionReadinessStatus.READY);
            assertThat(state.targetGeneration()).isEqualTo(3);
            assertThat(state.appliedSnapshot()).isEqualTo(newer.targetSnapshot());
        });
    }

    @Test
    void migrationRejectsIncompleteActiveOperationProof() {
        GraphWorkspaceScope workspace = insertWorkspace("migration-check");

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO graph_projection_lifecycle (
                            workspace_id, provider, projection_version, status,
                            target_generation, applied_generation, operation_kind,
                            operation_owner, operation_source_fingerprint, updated_at)
                        VALUES (:workspace, 'arcadedb', 'graph-projection-v1', 'BUILDING',
                            1, 0, 'REBUILD', 'owner', :fingerprint, '2026-09-05T00:00:00Z')
                        """)
                .param("workspace", workspace.id())
                .param("fingerprint", FIRST_FINGERPRINT)
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("SQLITE_CONSTRAINT_CHECK");
    }

    private GraphProjectionOperation reserve(GraphWorkspaceScope workspace,
                                               GraphProjectionOperationKind kind,
                                               String fingerprint, String owner) {
        return repository.reserve(workspace, PROVIDER, VERSION, kind, fingerprint, owner);
    }

    private GraphWorkspaceScope insertWorkspace(String name) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path,
                    data_path, status, created_at, updated_at)
                VALUES (:name, :root, :inbox, :archive, :vault, :data, 'ACTIVE',
                    '2026-09-05T00:00:00Z', '2026-09-05T00:00:00Z')
                """)
                .param("name", name)
                .param("root", "/tmp/graph-" + name)
                .param("inbox", "/tmp/graph-" + name + "/inbox")
                .param("archive", "/tmp/graph-" + name + "/archive")
                .param("vault", "/tmp/graph-" + name + "/vault")
                .param("data", "/tmp/graph-" + name + "/data")
                .update(key);
        return new GraphWorkspaceScope(key.getKey().longValue());
    }

    private static void assertProjectionFailure(GraphProjectionFailureType type,
                                                org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(GraphProjectionException.class)
                .extracting(failure -> ((GraphProjectionException) failure).failureType())
                .isEqualTo(type);
    }
}
