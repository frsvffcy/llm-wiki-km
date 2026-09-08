package org.km.llmwiki.graph;

import org.km.llmwiki.web.ApiResponse;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider-neutral operational REST boundary for the Graph projection: readiness query,
 * explicit canonical rebuild, and explicit repair.
 *
 * <p>The controller is an adapter only — request routing, DTO mapping, and HTTP/error mapping.
 * All policy lives in the application-owned {@link GraphProjectionOperations} implementation and
 * the SQLite-authoritative lifecycle: canonical input assembly, monotonic generations, CAS
 * publication, currentness, and workspace scoping are never re-implemented here, and no
 * backend adapter, path, record identity, or credential is ever touched or exposed. Destructive
 * {@code clear} is deliberately not part of this public surface. The operational target is the
 * active workspace, matching the existing public workspace contract. Ask stays read-only and
 * never triggers these operations.
 */
@RestController
@RequestMapping("/api/v1/graph/projection")
public class GraphProjectionController {

    private final GraphProjectionOperations operations;
    private final WorkspaceService workspaceService;

    public GraphProjectionController(GraphProjectionOperations operations,
                                     WorkspaceService workspaceService) {
        this.operations = operations;
        this.workspaceService = workspaceService;
    }

    @GetMapping("/readiness")
    public ApiResponse<GraphProjectionStatusResponse> readiness() {
        return new ApiResponse<>(GraphProjectionStatusResponse.from(
                operations.readiness(activeWorkspaceId())));
    }

    @PostMapping("/rebuild")
    public ApiResponse<GraphProjectionStatusResponse> rebuild() {
        long workspaceId = activeWorkspaceId();
        GraphProjectionVerification verification = operations.rebuild(workspaceId);
        return new ApiResponse<>(statusOrRefuse(verification));
    }

    @PostMapping("/repair")
    public ApiResponse<GraphProjectionStatusResponse> repair() {
        long workspaceId = activeWorkspaceId();
        GraphProjectionVerification verification = operations.repair(workspaceId);
        return new ApiResponse<>(statusOrRefuse(verification));
    }

    /**
     * A maintenance operation on a capability that is disabled or unconfigured did nothing;
     * reporting it as HTTP 200 success would hide the refusal, so it maps to a typed conflict.
     * Readiness, by contrast, is a status query and always reports the actual state.
     */
    private static GraphProjectionStatusResponse statusOrRefuse(
            GraphProjectionVerification verification) {
        if (verification.status() == GraphProjectionVerificationStatus.DISABLED) {
            throw new GraphProjectionException(GraphProjectionFailureType.CAPABILITY_DISABLED);
        }
        if (verification.status() == GraphProjectionVerificationStatus.NOT_CONFIGURED) {
            throw new GraphProjectionException(GraphProjectionFailureType.CONFIGURATION_INVALID);
        }
        return GraphProjectionStatusResponse.from(verification);
    }

    private long activeWorkspaceId() {
        return workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new).id();
    }
}
