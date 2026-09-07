package org.km.llmwiki.graph;

/** Production 維護入口。每次 rebuild/repair 重新組裝，generation 由 lifecycle 管理。 */
public final class GraphProjectionIngressService {
    private final GraphProjectionInputAssembler assembler;
    private final GraphProjectionLifecycleService lifecycle;

    public GraphProjectionIngressService(GraphProjectionInputAssembler assembler,
                                         GraphProjectionLifecycleService lifecycle) {
        this.assembler = java.util.Objects.requireNonNull(assembler);
        this.lifecycle = java.util.Objects.requireNonNull(lifecycle);
    }

    public GraphProjectionVerification readiness(long workspaceId) {
        return lifecycle.readiness(new GraphWorkspaceScope(workspaceId));
    }

    public GraphProjectionVerification rebuild(long workspaceId) {
        return build(workspaceId, false);
    }

    public GraphProjectionVerification repair(long workspaceId) {
        return build(workspaceId, true);
    }

    private GraphProjectionVerification build(long workspaceId, boolean repair) {
        var workspace = new GraphWorkspaceScope(workspaceId);
        var capability = lifecycle.readiness(workspace);
        if (capability.status() == GraphProjectionVerificationStatus.DISABLED
                || capability.status() == GraphProjectionVerificationStatus.NOT_CONFIGURED) {
            return capability;
        }
        GraphProjectionInput input = assembler.assemble(workspace);
        return repair ? lifecycle.repair(input) : lifecycle.rebuild(input);
    }
}
