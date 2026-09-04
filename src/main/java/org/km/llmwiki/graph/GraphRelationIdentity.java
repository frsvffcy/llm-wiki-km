package org.km.llmwiki.graph;

/** Application-owned deterministic identity for one directed graph relation. */
public record GraphRelationIdentity(GraphWorkspaceScope workspace, GraphEntityIdentity source,
                                    GraphRelationType type, GraphEntityIdentity target,
                                    String stableId) {

    public GraphRelationIdentity {
        if (workspace == null || source == null || type == null || target == null
                || !workspace.equals(source.workspace()) || !workspace.equals(target.workspace())) {
            throw new IllegalArgumentException("Graph relation identity is incomplete or cross-workspace");
        }
        String expected = GraphIdentityCodec.relationStableId(workspace, source, type, target);
        if (stableId == null || !expected.equals(stableId)) {
            throw new IllegalArgumentException("Graph relation stable id does not match relation identity");
        }
    }

    public GraphRelationIdentity(GraphEntityIdentity source, GraphRelationType type,
                                 GraphEntityIdentity target) {
        this(requireWorkspace(source, target), source, type, target,
                GraphIdentityCodec.relationStableId(requireWorkspace(source, target), source, type, target));
    }

    public static GraphRelationIdentity of(GraphEntityIdentity source, GraphRelationType type,
                                           GraphEntityIdentity target) {
        return new GraphRelationIdentity(source, type, target);
    }

    private static GraphWorkspaceScope requireWorkspace(GraphEntityIdentity source,
                                                        GraphEntityIdentity target) {
        if (source == null || target == null || !source.workspace().equals(target.workspace())) {
            throw new IllegalArgumentException("Graph relation endpoints must share a workspace");
        }
        return source.workspace();
    }
}
