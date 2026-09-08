package org.km.llmwiki.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.web.GlobalExceptionHandler;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for the provider-neutral Graph projection operational API: readiness is a
 * status query, rebuild/repair are explicit operations, and the failure taxonomy maps to
 * distinct typed errors without exposing backend or secret material.
 */
@Tag("integration")
@WebMvcTest(GraphProjectionController.class)
@Import(GlobalExceptionHandler.class)
class GraphProjectionApiIntegrationTest {

    private static final long WORKSPACE_ID = 41L;
    private static final GraphWorkspaceScope WORKSPACE = new GraphWorkspaceScope(WORKSPACE_ID);
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String SNAPSHOT_TOKEN = "b".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GraphProjectionOperations operations;

    @MockitoBean
    private WorkspaceService workspaceService;

    @BeforeEach
    void resetMocks() {
        reset(operations, workspaceService);
        when(workspaceService.findActiveWithoutValidation()).thenReturn(Optional.of(workspace()));
        when(operations.readiness(anyLong())).thenReturn(disabled());
    }

    @Test
    void readinessIsAStatusQueryThatAlwaysReportsTheActualState() throws Exception {
        when(operations.readiness(WORKSPACE_ID)).thenReturn(ready());

        mockMvc.perform(get("/api/v1/graph/projection/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspaceId").value(WORKSPACE_ID))
                .andExpect(jsonPath("$.data.provider").value("arcadedb"))
                .andExpect(jsonPath("$.data.projectionVersion").value("graph-projection-v2"))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.appliedGeneration").value(3))
                .andExpect(jsonPath("$.data.repairRecommended").value(false))
                // Secret control-plane material never reaches the response.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(FINGERPRINT))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(SNAPSHOT_TOKEN))));
    }

    @Test
    void disabledCapabilityIsReportedAsAStatusWithoutAnError() throws Exception {
        when(operations.readiness(WORKSPACE_ID)).thenReturn(disabled());

        mockMvc.perform(get("/api/v1/graph/projection/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
    }

    @Test
    void rebuildReturnsTheResultingReadyStatus() throws Exception {
        when(operations.rebuild(WORKSPACE_ID)).thenReturn(ready());

        mockMvc.perform(post("/api/v1/graph/projection/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.appliedGeneration").value(3));

        verify(operations).rebuild(WORKSPACE_ID);
    }

    @Test
    void repairReturnsTheResultingReadyStatus() throws Exception {
        when(operations.repair(WORKSPACE_ID)).thenReturn(ready());

        mockMvc.perform(post("/api/v1/graph/projection/repair"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"));
    }

    @Test
    void operationOnDisabledCapabilityIsRefusedAsTypedConflictNotFakeSuccess() throws Exception {
        when(operations.rebuild(WORKSPACE_ID)).thenReturn(disabled());

        mockMvc.perform(post("/api/v1/graph/projection/rebuild"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("GRAPH_CAPABILITY_DISABLED"));
    }

    @Test
    void operationOnUnconfiguredCapabilityIsRefusedAsTypedConflict() throws Exception {
        when(operations.rebuild(WORKSPACE_ID)).thenReturn(new GraphProjectionVerification(
                WORKSPACE, GraphProjectionVerificationStatus.NOT_CONFIGURED, null,
                GraphProjectionFailure.of(GraphProjectionFailureType.CONFIGURATION_INVALID)));

        mockMvc.perform(post("/api/v1/graph/projection/rebuild"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("GRAPH_CONFIGURATION_INVALID"));
    }

    @Test
    void supersededOperationMapsToTypedConflict() throws Exception {
        when(operations.rebuild(WORKSPACE_ID)).thenThrow(new GraphProjectionException(
                GraphProjectionFailureType.PROJECTION_STALE));

        mockMvc.perform(post("/api/v1/graph/projection/rebuild"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("GRAPH_PROJECTION_STALE"));
    }

    @Test
    void operationalBackendFailuresMapToTypedServiceUnavailable() throws Exception {
        org.mockito.Mockito.doThrow(new GraphProjectionException(
                GraphProjectionFailureType.BACKEND_LOCKED))
                .when(operations).rebuild(WORKSPACE_ID);

        mockMvc.perform(post("/api/v1/graph/projection/rebuild"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("GRAPH_BACKEND_LOCKED"));

        org.mockito.Mockito.doThrow(new GraphProjectionException(
                GraphProjectionFailureType.FILESYSTEM_UNAVAILABLE))
                .when(operations).repair(WORKSPACE_ID);

        mockMvc.perform(post("/api/v1/graph/projection/repair"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("GRAPH_FILESYSTEM_UNAVAILABLE"));
    }

    @Test
    void corruptProjectionFailsClosedAsInternalErrorAndIsNeverDisguisedAsUnavailable()
            throws Exception {
        when(operations.rebuild(WORKSPACE_ID)).thenThrow(new GraphProjectionException(
                GraphProjectionFailureType.PROJECTION_CORRUPT));

        mockMvc.perform(post("/api/v1/graph/projection/rebuild"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("GRAPH_PROJECTION_CORRUPT"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("at org."))));
    }

    @Test
    void missingActiveWorkspaceIsTypedNotFound() throws Exception {
        when(workspaceService.findActiveWithoutValidation()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/graph/projection/readiness"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_WORKSPACE"));
        verify(operations, org.mockito.Mockito.never()).readiness(anyLong());

        mockMvc.perform(post("/api/v1/graph/projection/rebuild"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_WORKSPACE"));
        verify(operations, org.mockito.Mockito.never()).rebuild(anyLong());
    }

    @Test
    void controllerOwnsNoBackendOrProjectionPolicy() {
        // The adapter stays thin: routing, DTO mapping, HTTP/error mapping only. Canonical
        // assembly, generations, CAS, currentness, and any backend handle stay out of reach.
        assertThat(java.util.List.of(GraphProjectionController.class.getDeclaredFields()))
                .allSatisfy(field -> {
                    String packageName = field.getType().getPackageName();
                    assertThat(packageName).doesNotStartWith("org.km.llmwiki.persistence");
                    String simpleName = field.getType().getSimpleName();
                    assertThat(simpleName).doesNotContain("Backend").doesNotContain("Arcade");
                });
    }

    private static WorkspaceResponse workspace() {
        return new WorkspaceResponse(WORKSPACE_ID, "test", "/root", "/root/inbox",
                "/root/archive", "/root/vault", "/root/data", "/root/config", "ACTIVE",
                "2026-09-09T00:00:00Z", "2026-09-09T00:00:00Z");
    }

    private static GraphProjectionVerification ready() {
        GraphProjectionReadiness control = new GraphProjectionReadiness(WORKSPACE, "arcadedb",
                GraphProjectionVersion.current(), GraphProjectionReadinessStatus.READY, 3, 3,
                FINGERPRINT, SNAPSHOT_TOKEN, null, null, null, null, null, null, "now", "now");
        return new GraphProjectionVerification(WORKSPACE,
                GraphProjectionVerificationStatus.READY, control, null);
    }

    private static GraphProjectionVerification disabled() {
        return new GraphProjectionVerification(WORKSPACE,
                GraphProjectionVerificationStatus.DISABLED, null, null);
    }
}
