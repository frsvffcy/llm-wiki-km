package org.km.llmwiki.graph;

/** Bounded application-owned consistency proof read from a disposable projection backend. */
public record GraphProjectionBackendProof(GraphWorkspaceScope workspace,
                                          GraphProjectionSnapshot currentSnapshot,
                                          GraphProjectionSnapshot clearedSnapshot) {
    public GraphProjectionBackendProof {
        if (workspace == null
                || currentSnapshot != null && !workspace.equals(currentSnapshot.workspace())
                || clearedSnapshot != null && !workspace.equals(clearedSnapshot.workspace())
                || currentSnapshot != null && clearedSnapshot != null) {
            throw new IllegalArgumentException("Graph projection backend proof is inconsistent");
        }
    }

    public static GraphProjectionBackendProof empty(GraphWorkspaceScope workspace) {
        return new GraphProjectionBackendProof(workspace, null, null);
    }
}
