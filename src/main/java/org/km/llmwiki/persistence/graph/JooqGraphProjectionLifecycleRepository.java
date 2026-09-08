package org.km.llmwiki.persistence.graph;

import org.jooq.DSLContext;
import org.jooq.Condition;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailure;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphProjectionOperation;
import org.km.llmwiki.graph.GraphProjectionOperationKind;
import org.km.llmwiki.graph.GraphProjectionReadiness;
import org.km.llmwiki.graph.GraphProjectionReadinessStatus;
import org.km.llmwiki.graph.GraphProjectionSnapshot;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.persistence.jooq.generated.tables.records.GraphProjectionLifecycleRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.km.llmwiki.persistence.jooq.generated.Tables.GRAPH_PROJECTION_LIFECYCLE;

/** SQLite-backed generation allocator and compare-and-set readiness authority. */
@Repository
public class JooqGraphProjectionLifecycleRepository implements GraphProjectionLifecycleRepository {

    private final DSLContext dsl;
    private final Clock clock;

    @Autowired
    public JooqGraphProjectionLifecycleRepository(DSLContext dsl) {
        this(dsl, Clock.systemUTC());
    }

    JooqGraphProjectionLifecycleRepository(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    @Override
    @Transactional
    public GraphProjectionOperation reserve(GraphWorkspaceScope workspace, String provider,
                                            GraphProjectionVersion projectionVersion,
                                            GraphProjectionOperationKind kind,
                                            String sourceFingerprint, String ownerToken) {
        requireReservation(workspace, provider, projectionVersion, kind, sourceFingerprint,
                ownerToken);
        int workspaceId = Math.toIntExact(workspace.id());
        // SQLite transactions are deferred by default. Start with a bounded write statement so
        // concurrent reservations serialize before either transaction reads the generation it
        // will supersede; this avoids a read-to-write upgrade race without a process-local lock.
        dsl.update(GRAPH_PROJECTION_LIFECYCLE)
                .set(GRAPH_PROJECTION_LIFECYCLE.UPDATED_AT,
                        GRAPH_PROJECTION_LIFECYCLE.UPDATED_AT)
                .where(GRAPH_PROJECTION_LIFECYCLE.WORKSPACE_ID.eq(workspaceId))
                .execute();
        GraphProjectionReadiness previous = find(workspace).orElse(null);
        if (previous != null && !previous.provider().equals(provider.strip())) {
            throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
        }
        boolean versionMigration = previous != null
                && !previous.projectionVersion().equals(projectionVersion);
        if (versionMigration && (kind != GraphProjectionOperationKind.REBUILD
                || !projectionVersion.permitsMigrationFrom(previous.projectionVersion()))) {
            throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
        }
        if (previous != null && previous.targetGeneration() >= Integer.MAX_VALUE) {
            throw new GraphProjectionException(new GraphProjectionFailure(
                    GraphProjectionFailureType.INVALID_PROJECTION_INPUT,
                    "graph projection generation is exhausted"));
        }
        // A version transition is a full replacement, never a repair of or continuation from
        // the old READY proof. Clearing the applied proof at reservation makes the migration
        // fail closed even if the process stops before the backend publishes the new version.
        GraphProjectionSnapshot expectedApplied = previous == null || versionMigration
                ? null : previous.appliedSnapshot();
        if (kind == GraphProjectionOperationKind.CLEAR
                && (expectedApplied == null
                || !expectedApplied.sourceFingerprint().equals(sourceFingerprint))) {
            throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_NOT_READY);
        }

        String timestamp = now();
        dsl.insertInto(GRAPH_PROJECTION_LIFECYCLE)
                .columns(GRAPH_PROJECTION_LIFECYCLE.WORKSPACE_ID,
                        GRAPH_PROJECTION_LIFECYCLE.PROVIDER,
                        GRAPH_PROJECTION_LIFECYCLE.PROJECTION_VERSION,
                        GRAPH_PROJECTION_LIFECYCLE.STATUS,
                        GRAPH_PROJECTION_LIFECYCLE.TARGET_GENERATION,
                        GRAPH_PROJECTION_LIFECYCLE.APPLIED_GENERATION,
                        GRAPH_PROJECTION_LIFECYCLE.OPERATION_OWNER,
                        GRAPH_PROJECTION_LIFECYCLE.OPERATION_KIND,
                        GRAPH_PROJECTION_LIFECYCLE.OPERATION_SOURCE_FINGERPRINT,
                        GRAPH_PROJECTION_LIFECYCLE.OPERATION_STARTED_AT,
                        GRAPH_PROJECTION_LIFECYCLE.UPDATED_AT)
                .values(workspaceId, provider.strip(), projectionVersion.value(),
                        kind.activeStatus().name(), 1, 0, ownerToken.strip(), kind.name(),
                        sourceFingerprint, timestamp, timestamp)
                .onConflict(GRAPH_PROJECTION_LIFECYCLE.WORKSPACE_ID)
                .doUpdate()
                .set(GRAPH_PROJECTION_LIFECYCLE.PROVIDER, provider.strip())
                .set(GRAPH_PROJECTION_LIFECYCLE.PROJECTION_VERSION, projectionVersion.value())
                .set(GRAPH_PROJECTION_LIFECYCLE.STATUS, kind.activeStatus().name())
                .set(GRAPH_PROJECTION_LIFECYCLE.TARGET_GENERATION,
                        GRAPH_PROJECTION_LIFECYCLE.TARGET_GENERATION.add(1))
                .set(GRAPH_PROJECTION_LIFECYCLE.APPLIED_GENERATION,
                        versionMigration ? 0 : previous == null ? 0
                                : Math.toIntExact(previous.appliedGeneration()))
                .set(GRAPH_PROJECTION_LIFECYCLE.SOURCE_FINGERPRINT,
                        versionMigration ? null : previous == null ? null
                                : previous.sourceFingerprint())
                .set(GRAPH_PROJECTION_LIFECYCLE.SNAPSHOT_TOKEN,
                        versionMigration ? null : previous == null ? null
                                : previous.snapshotToken())
                .set(GRAPH_PROJECTION_LIFECYCLE.OPERATION_OWNER, ownerToken.strip())
                .set(GRAPH_PROJECTION_LIFECYCLE.OPERATION_KIND, kind.name())
                .set(GRAPH_PROJECTION_LIFECYCLE.OPERATION_SOURCE_FINGERPRINT, sourceFingerprint)
                .set(GRAPH_PROJECTION_LIFECYCLE.OPERATION_STARTED_AT, timestamp)
                .set(GRAPH_PROJECTION_LIFECYCLE.LAST_FAILURE_TYPE, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.DIAGNOSTIC_CODE, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.LAST_SUCCESSFUL_AT,
                        versionMigration ? null : previous == null ? null
                                : previous.lastSuccessfulAt())
                .set(GRAPH_PROJECTION_LIFECYCLE.UPDATED_AT, timestamp)
                .execute();

        GraphProjectionReadiness reserved = find(workspace)
                .orElseThrow(() -> new IllegalStateException("Graph projection reservation is missing"));
        if (!ownerToken.strip().equals(reserved.operationOwner())
                || !provider.strip().equals(reserved.provider())
                || !projectionVersion.equals(reserved.projectionVersion())) {
            throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE);
        }
        return new GraphProjectionOperation(workspace, provider, projectionVersion, kind,
                reserved.targetGeneration(), ownerToken, sourceFingerprint, timestamp,
                expectedApplied);
    }

    @Override
    public Optional<GraphProjectionReadiness> find(GraphWorkspaceScope workspace) {
        requireWorkspace(workspace);
        return dsl.selectFrom(GRAPH_PROJECTION_LIFECYCLE)
                .where(GRAPH_PROJECTION_LIFECYCLE.WORKSPACE_ID.eq(Math.toIntExact(workspace.id())))
                .fetchOptional(this::map);
    }

    @Override
    public List<GraphProjectionReadiness> findActive() {
        return dsl.selectFrom(GRAPH_PROJECTION_LIFECYCLE)
                .where(GRAPH_PROJECTION_LIFECYCLE.STATUS.in(
                        GraphProjectionReadinessStatus.BUILDING.name(),
                        GraphProjectionReadinessStatus.REPAIRING.name(),
                        GraphProjectionReadinessStatus.CLEARING.name()))
                .orderBy(GRAPH_PROJECTION_LIFECYCLE.WORKSPACE_ID.asc())
                .fetch(this::map);
    }

    @Override
    @Transactional
    public boolean markReady(GraphProjectionOperation operation, GraphProjectionSnapshot snapshot) {
        requireOperation(operation);
        if (operation.kind() == GraphProjectionOperationKind.CLEAR
                || snapshot == null || !snapshot.equals(operation.targetSnapshot())) {
            throw new IllegalArgumentException("Graph projection READY proof does not match its operation");
        }
        String timestamp = now();
        return dsl.update(GRAPH_PROJECTION_LIFECYCLE)
                .set(GRAPH_PROJECTION_LIFECYCLE.STATUS, GraphProjectionReadinessStatus.READY.name())
                .set(GRAPH_PROJECTION_LIFECYCLE.APPLIED_GENERATION,
                        Math.toIntExact(snapshot.generation()))
                .set(GRAPH_PROJECTION_LIFECYCLE.SOURCE_FINGERPRINT, snapshot.sourceFingerprint())
                .set(GRAPH_PROJECTION_LIFECYCLE.SNAPSHOT_TOKEN, snapshot.snapshotToken())
                .set(GRAPH_PROJECTION_LIFECYCLE.OPERATION_OWNER, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.OPERATION_KIND, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.OPERATION_SOURCE_FINGERPRINT, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.OPERATION_STARTED_AT, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.LAST_FAILURE_TYPE, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.DIAGNOSTIC_CODE, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.LAST_SUCCESSFUL_AT, timestamp)
                .set(GRAPH_PROJECTION_LIFECYCLE.UPDATED_AT, timestamp)
                .where(ownershipCondition(operation))
                .execute() == 1;
    }

    @Override
    @Transactional
    public boolean markCleared(GraphProjectionOperation operation) {
        requireOperation(operation);
        GraphProjectionSnapshot expected = operation.expectedAppliedSnapshot();
        if (operation.kind() != GraphProjectionOperationKind.CLEAR || expected == null) {
            throw new IllegalArgumentException("Graph projection clear proof is incomplete");
        }
        String timestamp = now();
        return dsl.update(GRAPH_PROJECTION_LIFECYCLE)
                .set(GRAPH_PROJECTION_LIFECYCLE.STATUS,
                        GraphProjectionReadinessStatus.NOT_READY.name())
                .set(GRAPH_PROJECTION_LIFECYCLE.APPLIED_GENERATION, 0)
                .set(GRAPH_PROJECTION_LIFECYCLE.SOURCE_FINGERPRINT, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.SNAPSHOT_TOKEN, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.OPERATION_OWNER, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.OPERATION_KIND, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.OPERATION_SOURCE_FINGERPRINT, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.OPERATION_STARTED_AT, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.LAST_FAILURE_TYPE, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.DIAGNOSTIC_CODE, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.UPDATED_AT, timestamp)
                .where(ownershipCondition(operation))
                .and(GRAPH_PROJECTION_LIFECYCLE.APPLIED_GENERATION.eq(
                        Math.toIntExact(expected.generation())))
                .and(GRAPH_PROJECTION_LIFECYCLE.SOURCE_FINGERPRINT.eq(expected.sourceFingerprint()))
                .and(GRAPH_PROJECTION_LIFECYCLE.SNAPSHOT_TOKEN.eq(expected.snapshotToken()))
                .execute() == 1;
    }

    @Override
    @Transactional
    public boolean markFailed(GraphProjectionOperation operation, GraphProjectionFailure failure) {
        requireOperation(operation);
        if (failure == null) {
            throw new IllegalArgumentException("Graph projection failure is required");
        }
        GraphProjectionReadinessStatus status = operation.expectedAppliedSnapshot() == null
                ? GraphProjectionReadinessStatus.FAILED
                : GraphProjectionReadinessStatus.DEGRADED;
        return dsl.update(GRAPH_PROJECTION_LIFECYCLE)
                .set(GRAPH_PROJECTION_LIFECYCLE.STATUS, status.name())
                .set(GRAPH_PROJECTION_LIFECYCLE.OPERATION_OWNER, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.OPERATION_KIND, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.OPERATION_SOURCE_FINGERPRINT, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.OPERATION_STARTED_AT, (String) null)
                .set(GRAPH_PROJECTION_LIFECYCLE.LAST_FAILURE_TYPE, failure.type().name())
                .set(GRAPH_PROJECTION_LIFECYCLE.DIAGNOSTIC_CODE, failure.type().publicCode())
                .set(GRAPH_PROJECTION_LIFECYCLE.UPDATED_AT, now())
                .where(ownershipCondition(operation))
                .execute() == 1;
    }

    @Override
    @Transactional
    public boolean markDegraded(GraphWorkspaceScope workspace,
                                GraphProjectionSnapshot expectedSnapshot,
                                GraphProjectionFailure failure) {
        requireWorkspace(workspace);
        if (expectedSnapshot == null || failure == null
                || !workspace.equals(expectedSnapshot.workspace())) {
            throw new IllegalArgumentException("Graph projection degradation proof is incomplete");
        }
        return dsl.update(GRAPH_PROJECTION_LIFECYCLE)
                .set(GRAPH_PROJECTION_LIFECYCLE.STATUS,
                        GraphProjectionReadinessStatus.DEGRADED.name())
                .set(GRAPH_PROJECTION_LIFECYCLE.LAST_FAILURE_TYPE, failure.type().name())
                .set(GRAPH_PROJECTION_LIFECYCLE.DIAGNOSTIC_CODE, failure.type().publicCode())
                .set(GRAPH_PROJECTION_LIFECYCLE.UPDATED_AT, now())
                .where(GRAPH_PROJECTION_LIFECYCLE.WORKSPACE_ID.eq(
                        Math.toIntExact(workspace.id())))
                .and(GRAPH_PROJECTION_LIFECYCLE.STATUS.eq(
                        GraphProjectionReadinessStatus.READY.name()))
                .and(GRAPH_PROJECTION_LIFECYCLE.APPLIED_GENERATION.eq(
                        Math.toIntExact(expectedSnapshot.generation())))
                .and(GRAPH_PROJECTION_LIFECYCLE.SOURCE_FINGERPRINT.eq(
                        expectedSnapshot.sourceFingerprint()))
                .and(GRAPH_PROJECTION_LIFECYCLE.SNAPSHOT_TOKEN.eq(
                        expectedSnapshot.snapshotToken()))
                .execute() == 1;
    }

    private Condition ownershipCondition(GraphProjectionOperation operation) {
        return GRAPH_PROJECTION_LIFECYCLE.WORKSPACE_ID.eq(
                        Math.toIntExact(operation.workspace().id()))
                .and(GRAPH_PROJECTION_LIFECYCLE.PROVIDER.eq(operation.provider()))
                .and(GRAPH_PROJECTION_LIFECYCLE.PROJECTION_VERSION.eq(
                        operation.projectionVersion().value()))
                .and(GRAPH_PROJECTION_LIFECYCLE.TARGET_GENERATION.eq(
                        Math.toIntExact(operation.generation())))
                .and(GRAPH_PROJECTION_LIFECYCLE.OPERATION_OWNER.eq(operation.ownerToken()))
                .and(GRAPH_PROJECTION_LIFECYCLE.OPERATION_KIND.eq(operation.kind().name()))
                .and(GRAPH_PROJECTION_LIFECYCLE.STATUS.eq(operation.kind().activeStatus().name()));
    }

    private GraphProjectionReadiness map(GraphProjectionLifecycleRecord record) {
        GraphProjectionOperationKind operationKind = record.getOperationKind() == null ? null
                : GraphProjectionOperationKind.valueOf(record.getOperationKind());
        GraphProjectionFailureType failureType = record.getLastFailureType() == null ? null
                : GraphProjectionFailureType.valueOf(record.getLastFailureType());
        return new GraphProjectionReadiness(
                new GraphWorkspaceScope(record.getWorkspaceId().longValue()),
                record.getProvider(), new GraphProjectionVersion(record.getProjectionVersion()),
                GraphProjectionReadinessStatus.valueOf(record.getStatus()),
                record.getTargetGeneration().longValue(), record.getAppliedGeneration().longValue(),
                record.getSourceFingerprint(), record.getSnapshotToken(), operationKind,
                record.getOperationOwner(), record.getOperationSourceFingerprint(),
                record.getOperationStartedAt(), failureType, record.getDiagnosticCode(),
                record.getLastSuccessfulAt(), record.getUpdatedAt());
    }

    private static void requireReservation(GraphWorkspaceScope workspace, String provider,
                                           GraphProjectionVersion version,
                                           GraphProjectionOperationKind kind,
                                           String fingerprint, String ownerToken) {
        requireWorkspace(workspace);
        if (provider == null || provider.isBlank() || version == null || kind == null
                || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")
                || ownerToken == null || ownerToken.isBlank()) {
            throw new IllegalArgumentException("Graph projection reservation is incomplete");
        }
    }

    private static void requireOperation(GraphProjectionOperation operation) {
        if (operation == null) {
            throw new IllegalArgumentException("Graph projection operation is required");
        }
    }

    private static void requireWorkspace(GraphWorkspaceScope workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("Graph workspace is required");
        }
    }

    private String now() {
        return Instant.now(clock).toString();
    }
}
