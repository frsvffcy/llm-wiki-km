package org.km.llmwiki.graph;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract for the safe public projection of graph projection verification state: lifecycle
 * status, generations, and typed failure codes travel to the operator; fingerprints, snapshot
 * tokens, owner tokens, and backend identities never do.
 */
@Tag("unit")
class GraphProjectionStatusResponseTest {

    private static final GraphWorkspaceScope WORKSPACE = new GraphWorkspaceScope(41);
    private static final GraphProjectionVersion VERSION = GraphProjectionVersion.current();
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String SNAPSHOT_TOKEN = "b".repeat(64);
    private static final String OWNER_TOKEN = "owner-secret-token";

    @Test
    void readyVerificationProjectsSafeLifecycleStateWithoutAnySecretMaterial() {
        GraphProjectionVerification verification = new GraphProjectionVerification(WORKSPACE,
                GraphProjectionVerificationStatus.READY, readyControl(), null);

        GraphProjectionStatusResponse response = GraphProjectionStatusResponse.from(verification);

        assertThat(response.workspaceId()).isEqualTo(41);
        assertThat(response.provider()).isEqualTo("arcadedb");
        assertThat(response.projectionVersion()).isEqualTo("graph-projection-v2");
        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.targetGeneration()).isEqualTo(3);
        assertThat(response.appliedGeneration()).isEqualTo(3);
        assertThat(response.operationKind()).isNull();
        assertThat(response.failureCode()).isNull();
        assertThat(response.failureDiagnostic()).isNull();
        assertThat(response.retryable()).isFalse();
        assertThat(response.repairRecommended()).isFalse();

        assertThat(valuesOf(response)).allSatisfy(value ->
                assertThat(value).doesNotContain(FINGERPRINT).doesNotContain(SNAPSHOT_TOKEN)
                        .doesNotContain(OWNER_TOKEN));
    }

    @Test
    void responseStructureExposesNoFingerprintTokenOwnerOrPathFields() {
        List<String> components = java.util.Arrays.stream(
                        GraphProjectionStatusResponse.class.getRecordComponents())
                .map(RecordComponent::getName).toList();

        assertThat(components).contains(
                "workspaceId", "provider", "projectionVersion", "status", "targetGeneration",
                "appliedGeneration", "operationKind", "failureCode", "failureDiagnostic",
                "lastFailureCode", "retryable", "repairRecommended");
        assertThat(components).noneMatch(name -> name.toLowerCase().contains("fingerprint"));
        assertThat(components).noneMatch(name -> name.toLowerCase().contains("token"));
        assertThat(components).noneMatch(name -> name.toLowerCase().contains("owner"));
        assertThat(components).noneMatch(name -> name.toLowerCase().contains("path"));
    }

    @Test
    void activeOperationIsReportedWithItsKindWithoutOwnerMaterial() {
        GraphProjectionVerification verification = new GraphProjectionVerification(WORKSPACE,
                GraphProjectionVerificationStatus.BUILDING, buildingControl(), null);

        GraphProjectionStatusResponse response = GraphProjectionStatusResponse.from(verification);

        assertThat(response.status()).isEqualTo("BUILDING");
        assertThat(response.operationKind()).isEqualTo("REBUILD");
        assertThat(response.appliedGeneration()).isZero();
        assertThat(response.repairRecommended()).isFalse();
        assertThat(valuesOf(response)).allSatisfy(value ->
                assertThat(value).doesNotContain(OWNER_TOKEN));
    }

    @Test
    void staleProjectionIsRetryableAndRecommendsRepair() {
        GraphProjectionVerification verification = new GraphProjectionVerification(WORKSPACE,
                GraphProjectionVerificationStatus.STALE, readyControl(),
                GraphProjectionFailure.of(GraphProjectionFailureType.PROJECTION_STALE));

        GraphProjectionStatusResponse response = GraphProjectionStatusResponse.from(verification);

        assertThat(response.failureCode()).isEqualTo("GRAPH_PROJECTION_STALE");
        assertThat(response.retryable()).isTrue();
        assertThat(response.repairRecommended()).isTrue();
    }

    @Test
    void repairRequiredStatusRecommendsRepairWithoutAFailureObject() {
        GraphProjectionVerification verification = new GraphProjectionVerification(WORKSPACE,
                GraphProjectionVerificationStatus.REPAIR_REQUIRED, readyControl(), null);

        GraphProjectionStatusResponse response = GraphProjectionStatusResponse.from(verification);

        assertThat(response.repairRecommended()).isTrue();
        assertThat(response.retryable()).isFalse();
    }

    @Test
    void operationalBackendFailureIsRetryableWithoutRepairRecommendation() {
        GraphProjectionVerification verification = new GraphProjectionVerification(WORKSPACE,
                GraphProjectionVerificationStatus.BACKEND_UNAVAILABLE, readyControl(),
                GraphProjectionFailure.of(GraphProjectionFailureType.BACKEND_FAILURE));

        GraphProjectionStatusResponse response = GraphProjectionStatusResponse.from(verification);

        assertThat(response.failureCode()).isEqualTo("GRAPH_BACKEND_FAILURE");
        assertThat(response.retryable()).isTrue();
        assertThat(response.repairRecommended()).isFalse();
    }

    @Test
    void corruptProjectionIsNotRetryableAndRecommendsRepair() {
        GraphProjectionVerification verification = new GraphProjectionVerification(WORKSPACE,
                GraphProjectionVerificationStatus.STALE, readyControl(),
                GraphProjectionFailure.of(GraphProjectionFailureType.PROJECTION_CORRUPT));

        GraphProjectionStatusResponse response = GraphProjectionStatusResponse.from(verification);

        assertThat(response.failureCode()).isEqualTo("GRAPH_PROJECTION_CORRUPT");
        assertThat(response.retryable()).isFalse();
        assertThat(response.repairRecommended()).isTrue();
    }

    @Test
    void disabledCapabilityWithoutControlPlaneProjectsZerosAndNoProvider() {
        GraphProjectionVerification verification = new GraphProjectionVerification(WORKSPACE,
                GraphProjectionVerificationStatus.DISABLED, null, null);

        GraphProjectionStatusResponse response = GraphProjectionStatusResponse.from(verification);

        assertThat(response.status()).isEqualTo("DISABLED");
        assertThat(response.provider()).isNull();
        assertThat(response.projectionVersion()).isNull();
        assertThat(response.targetGeneration()).isZero();
        assertThat(response.appliedGeneration()).isZero();
        assertThat(response.operationKind()).isNull();
        assertThat(response.failureCode()).isNull();
    }

    private static GraphProjectionReadiness readyControl() {
        return new GraphProjectionReadiness(WORKSPACE, "arcadedb", VERSION,
                GraphProjectionReadinessStatus.READY, 3, 3, FINGERPRINT, SNAPSHOT_TOKEN,
                null, null, null, null, null, null, "now", "now");
    }

    private static GraphProjectionReadiness buildingControl() {
        return new GraphProjectionReadiness(WORKSPACE, "arcadedb", VERSION,
                GraphProjectionReadinessStatus.BUILDING, 2, 0, null, null,
                GraphProjectionOperationKind.REBUILD, OWNER_TOKEN, "c".repeat(64), "now",
                null, null, null, "now");
    }

    private static List<String> valuesOf(GraphProjectionStatusResponse response) {
        return List.of(String.valueOf(response.workspaceId()),
                String.valueOf(response.provider()), String.valueOf(response.projectionVersion()),
                String.valueOf(response.status()), String.valueOf(response.targetGeneration()),
                String.valueOf(response.appliedGeneration()),
                String.valueOf(response.operationKind()), String.valueOf(response.failureCode()),
                String.valueOf(response.failureDiagnostic()),
                String.valueOf(response.lastFailureCode()));
    }
}
