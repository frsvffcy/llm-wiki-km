package org.km.llmwiki.persistence.graph.arcadedb;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.GraphEntity;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphProjectionLifecycleService;
import org.km.llmwiki.graph.GraphProjectionOperation;
import org.km.llmwiki.graph.GraphProjectionOperationKind;
import org.km.llmwiki.graph.GraphProjectionReadinessStatus;
import org.km.llmwiki.graph.GraphProjectionVerification;
import org.km.llmwiki.graph.GraphProjectionVerificationStatus;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.graph.GraphProjectionWriteContext;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class ArcadeDbGraphProjectionLifecycleIntegrationTest extends IsolatedIntegrationTest {

    private static final String PROVIDER = ArcadeDbGraphProjectionBackendFactory.PROVIDER;
    private static final GraphProjectionVersion VERSION = GraphProjectionVersion.initial();

    @Autowired
    GraphProjectionLifecycleRepository repository;

    @Autowired
    JdbcClient jdbc;

    @TempDir
    Path tempDir;

    @Test
    void rebuildRestartClearAndRebuildKeepSqliteGenerationMonotonic() {
        GraphWorkspaceScope workspace = insertWorkspace("restart");
        GraphProjectionInput input = input(workspace, "restart-page");
        Path basePath = tempDir.resolve("restart");

        GraphProjectionVerification initial;
        try (GraphProjectionLifecycleService service = service(basePath)) {
            initial = service.rebuild(input);
            assertThat(initial.status()).isEqualTo(GraphProjectionVerificationStatus.READY);
            assertThat(initial.controlPlane().targetGeneration()).isEqualTo(1);
        }

        try (GraphProjectionLifecycleService restarted = service(basePath)) {
            assertThat(restarted.readiness(workspace)).satisfies(verification -> {
                assertThat(verification.status())
                        .isEqualTo(GraphProjectionVerificationStatus.READY);
                assertThat(verification.controlPlane().appliedSnapshot())
                        .isEqualTo(initial.controlPlane().appliedSnapshot());
            });

            GraphProjectionVerification cleared = restarted.clear(workspace);
            assertThat(cleared.status()).isEqualTo(GraphProjectionVerificationStatus.NOT_READY);
            assertThat(cleared.controlPlane().targetGeneration()).isEqualTo(2);
            assertThat(cleared.controlPlane().appliedGeneration()).isZero();

            GraphProjectionVerification rebuilt = restarted.rebuild(input);
            assertThat(rebuilt.status()).isEqualTo(GraphProjectionVerificationStatus.READY);
            assertThat(rebuilt.controlPlane().targetGeneration()).isEqualTo(3);
            assertThat(rebuilt.controlPlane().snapshotToken())
                    .isNotEqualTo(initial.controlPlane().snapshotToken());
        }
    }

    @Test
    void missingDerivedDatabaseDegradesReadyProofAndRepairRebuildsNewGeneration()
            throws IOException {
        GraphWorkspaceScope workspace = insertWorkspace("missing-backend");
        GraphProjectionInput input = input(workspace, "missing-page");
        Path basePath = tempDir.resolve("missing-backend");
        Path workspacePath;

        try (GraphProjectionLifecycleService service = service(basePath)) {
            assertThat(service.rebuild(input).controlPlane().targetGeneration()).isEqualTo(1);
            workspacePath = factory(basePath).workspacePath(workspace);
        }
        deleteDerivedDatabase(workspacePath);

        try (GraphProjectionLifecycleService restarted = service(basePath)) {
            GraphProjectionVerification degraded = restarted.readiness(workspace);
            assertThat(degraded.status())
                    .isEqualTo(GraphProjectionVerificationStatus.REPAIR_REQUIRED);
            assertThat(degraded.controlPlane().status())
                    .isEqualTo(GraphProjectionReadinessStatus.DEGRADED);

            GraphProjectionVerification repaired = restarted.repair(input);
            assertThat(repaired.status()).isEqualTo(GraphProjectionVerificationStatus.READY);
            assertThat(repaired.controlPlane().targetGeneration()).isEqualTo(2);
            assertThat(Files.isDirectory(workspacePath)).isTrue();
        }
    }

    @Test
    void restartFailsClosedWhenProcessStoppedBeforeBackendWrite() {
        GraphWorkspaceScope workspace = insertWorkspace("before-write");
        GraphProjectionInput input = input(workspace, "before-write-page");
        Path basePath = tempDir.resolve("before-write");
        GraphProjectionOperation interrupted = reserve(input,
                GraphProjectionOperationKind.REBUILD, "before-write-owner");

        try (GraphProjectionLifecycleService restarted = service(basePath)) {
            restarted.reconcileInterruptedOperations();

            assertThat(repository.find(workspace).orElseThrow()).satisfies(state -> {
                assertThat(state.status()).isEqualTo(GraphProjectionReadinessStatus.FAILED);
                assertThat(state.targetGeneration()).isEqualTo(interrupted.generation());
                assertThat(state.lastFailureType().publicCode())
                        .isEqualTo("GRAPH_PROJECTION_NOT_READY");
            });

            GraphProjectionVerification rebuilt = restarted.rebuild(input);
            assertThat(rebuilt.status()).isEqualTo(GraphProjectionVerificationStatus.READY);
            assertThat(rebuilt.controlPlane().targetGeneration()).isEqualTo(2);
        }
    }

    @Test
    void restartRejectsStagedRowsUntilACompleteNewGenerationIsPublished() {
        GraphWorkspaceScope workspace = insertWorkspace("staged");
        GraphProjectionInput input = input(workspace, "staged-page");
        GraphEntity entity = input.entities().getFirst();
        Path basePath = tempDir.resolve("staged");
        GraphProjectionOperation interrupted = reserve(input,
                GraphProjectionOperationKind.REBUILD, "staged-owner");
        GraphProjectionWriteContext context = GraphProjectionWriteContext.of(
                input, interrupted.targetSnapshot());

        try (var writer = new ArcadeDbGraphProjectionWriter(
                factory(basePath).workspacePath(workspace))) {
            writer.upsertEntity(context, entity);
            assertThat(writer.hasStagedEntity(context, entity.identity())).isTrue();
            assertThat(writer.currentSnapshot(workspace)).isEmpty();
        }

        try (GraphProjectionLifecycleService restarted = service(basePath)) {
            restarted.reconcileInterruptedOperations();
            assertThat(repository.find(workspace).orElseThrow()).satisfies(state -> {
                assertThat(state.status()).isEqualTo(GraphProjectionReadinessStatus.FAILED);
                assertThat(state.lastFailureType().publicCode())
                        .isEqualTo("GRAPH_PROJECTION_STALE");
            });

            GraphProjectionVerification repaired = restarted.repair(input);
            assertThat(repaired.status()).isEqualTo(GraphProjectionVerificationStatus.READY);
            assertThat(repaired.controlPlane().targetGeneration()).isEqualTo(2);
        }
    }

    @Test
    void restartCommitsReadyWhenBackendPublishedBeforeSqliteCallback() {
        GraphWorkspaceScope workspace = insertWorkspace("published");
        GraphProjectionInput input = input(workspace, "published-page");
        Path basePath = tempDir.resolve("published");
        GraphProjectionOperation interrupted = reserve(input,
                GraphProjectionOperationKind.REBUILD, "published-owner");

        try (var writer = new ArcadeDbGraphProjectionWriter(
                factory(basePath).workspacePath(workspace))) {
            assertThat(new ArcadeDbGraphProjectionRebuilder(writer, interrupted.targetSnapshot())
                    .rebuild(input)).isEqualTo(interrupted.targetSnapshot());
        }
        assertThat(repository.find(workspace).orElseThrow().status())
                .isEqualTo(GraphProjectionReadinessStatus.BUILDING);

        try (GraphProjectionLifecycleService restarted = service(basePath)) {
            restarted.reconcileInterruptedOperations();
            assertThat(restarted.readiness(workspace)).satisfies(verification -> {
                assertThat(verification.status())
                        .isEqualTo(GraphProjectionVerificationStatus.READY);
                assertThat(verification.controlPlane().appliedSnapshot())
                        .isEqualTo(interrupted.targetSnapshot());
            });
        }
    }

    @Test
    void workspaceLifecyclesRemainIsolatedAcrossRebuildAndClear() {
        GraphWorkspaceScope firstWorkspace = insertWorkspace("workspace-a");
        GraphWorkspaceScope secondWorkspace = insertWorkspace("workspace-b");
        GraphProjectionInput firstInput = input(firstWorkspace, "page-a");
        GraphProjectionInput secondInput = input(secondWorkspace, "page-b");
        Path basePath = tempDir.resolve("workspace-isolation");

        try (GraphProjectionLifecycleService service = service(basePath)) {
            GraphProjectionVerification first = service.rebuild(firstInput);
            GraphProjectionVerification second = service.rebuild(secondInput);

            assertThat(first.status()).isEqualTo(GraphProjectionVerificationStatus.READY);
            assertThat(second.status()).isEqualTo(GraphProjectionVerificationStatus.READY);
            assertThat(factory(basePath).workspacePath(firstWorkspace))
                    .isNotEqualTo(factory(basePath).workspacePath(secondWorkspace));

            assertThat(service.clear(firstWorkspace).status())
                    .isEqualTo(GraphProjectionVerificationStatus.NOT_READY);
            assertThat(service.readiness(firstWorkspace).status())
                    .isEqualTo(GraphProjectionVerificationStatus.NOT_READY);
            assertThat(service.readiness(secondWorkspace)).satisfies(verification -> {
                assertThat(verification.status())
                        .isEqualTo(GraphProjectionVerificationStatus.READY);
                assertThat(verification.controlPlane().appliedSnapshot())
                        .isEqualTo(second.controlPlane().appliedSnapshot());
            });
        }
    }

    @Test
    void incompatiblePersistedBackendProofDegradesSqliteReadyState() throws IOException {
        GraphWorkspaceScope workspace = insertWorkspace("incompatible-proof");
        GraphProjectionInput currentInput = input(workspace, "current-page");
        GraphProjectionVersion incompatibleVersion =
                new GraphProjectionVersion("graph-projection-v2");
        GraphProjectionInput incompatibleInput = new GraphProjectionInput(workspace,
                incompatibleVersion, currentInput.entities().stream()
                .map(entity -> new GraphEntity(entity.identity(), entity.displayName(),
                        entity.provenance(), entity.metadata(), incompatibleVersion))
                .toList(), currentInput.relations());
        Path basePath = tempDir.resolve("incompatible-proof");
        Path workspacePath;

        try (GraphProjectionLifecycleService service = service(basePath)) {
            assertThat(service.rebuild(currentInput).status())
                    .isEqualTo(GraphProjectionVerificationStatus.READY);
            workspacePath = factory(basePath).workspacePath(workspace);
        }
        deleteDerivedDatabase(workspacePath);

        GraphProjectionOperation incompatibleOperation = new GraphProjectionOperation(workspace,
                PROVIDER, incompatibleVersion, GraphProjectionOperationKind.REBUILD, 1,
                "incompatible-owner", incompatibleInput.sourceFingerprint(),
                "2026-09-07T00:00:00Z", null);
        try (var writer = new ArcadeDbGraphProjectionWriter(workspacePath)) {
            new ArcadeDbGraphProjectionRebuilder(writer,
                    incompatibleOperation.targetSnapshot()).rebuild(incompatibleInput);
        }

        try (GraphProjectionLifecycleService service = service(basePath)) {
            assertThat(service.readiness(workspace)).satisfies(verification -> {
                assertThat(verification.status())
                        .isEqualTo(GraphProjectionVerificationStatus.PROJECTION_INCOMPATIBLE);
                assertThat(verification.controlPlane().status())
                        .isEqualTo(GraphProjectionReadinessStatus.DEGRADED);
                assertThat(verification.failure().publicCode())
                        .isEqualTo("GRAPH_PROJECTION_INCOMPATIBLE");
            });
        }
    }

    private GraphProjectionLifecycleService service(Path basePath) {
        return new GraphProjectionLifecycleService(true, PROVIDER, VERSION, repository,
                factory(basePath));
    }

    private ArcadeDbGraphProjectionBackendFactory factory(Path basePath) {
        return new ArcadeDbGraphProjectionBackendFactory(basePath, VERSION);
    }

    private GraphProjectionOperation reserve(GraphProjectionInput input,
                                               GraphProjectionOperationKind kind,
                                               String owner) {
        return repository.reserve(input.workspace(), PROVIDER, VERSION, kind,
                input.sourceFingerprint(), owner);
    }

    private static GraphProjectionInput input(GraphWorkspaceScope workspace, String id) {
        return ArcadeDbGraphProjectionFixtures.input(workspace,
                ArcadeDbGraphProjectionFixtures.page(workspace, id, "生命週期測試頁"));
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
                .param("root", "/tmp/graph-lifecycle-" + name)
                .param("inbox", "/tmp/graph-lifecycle-" + name + "/inbox")
                .param("archive", "/tmp/graph-lifecycle-" + name + "/archive")
                .param("vault", "/tmp/graph-lifecycle-" + name + "/vault")
                .param("data", "/tmp/graph-lifecycle-" + name + "/data")
                .update(key);
        return new GraphWorkspaceScope(key.getKey().longValue());
    }

    private void deleteDerivedDatabase(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(tempDir.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Derived test database escaped its temporary root");
        }
        if (!Files.exists(normalized)) {
            return;
        }
        try (var paths = Files.walk(normalized)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }
}
