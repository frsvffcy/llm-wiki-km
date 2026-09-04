package org.km.llmwiki.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Workspace-scoped active set used to remove or supersede stale derived projection state. */
public record GraphProjectionReconciliation(GraphWorkspaceScope workspace,
                                            GraphProjectionSnapshot snapshot,
                                            List<GraphEntityIdentity> activeEntities,
                                            List<GraphRelationIdentity> activeRelations) {

    public GraphProjectionReconciliation {
        if (workspace == null || snapshot == null || !workspace.equals(snapshot.workspace())
                || activeEntities == null || activeRelations == null) {
            throw new IllegalArgumentException("Graph reconciliation is incomplete or cross-workspace");
        }
        activeEntities = sortedEntities(workspace, activeEntities);
        activeRelations = sortedRelations(workspace, activeRelations);
        Set<GraphEntityIdentity> activeEntitySet = Set.copyOf(activeEntities);
        for (GraphRelationIdentity relation : activeRelations) {
            if (!activeEntitySet.contains(relation.source())
                    || !activeEntitySet.contains(relation.target())) {
                throw new IllegalArgumentException("Graph reconciliation contains an orphan relation");
            }
        }
    }

    private static List<GraphEntityIdentity> sortedEntities(GraphWorkspaceScope workspace,
                                                             List<GraphEntityIdentity> input) {
        List<GraphEntityIdentity> result = new ArrayList<>(input);
        if (result.stream().anyMatch(identity -> identity == null)) {
            throw new IllegalArgumentException("Graph reconciliation contains a null entity identity");
        }
        result.sort(java.util.Comparator.comparing(GraphEntityIdentity::stableId));
        for (int i = 0; i < result.size(); i++) {
            if (!workspace.equals(result.get(i).workspace())
                    || (i > 0 && result.get(i - 1).equals(result.get(i)))) {
                throw new IllegalArgumentException("Graph reconciliation contains invalid entity identity");
            }
        }
        return List.copyOf(result);
    }

    private static List<GraphRelationIdentity> sortedRelations(GraphWorkspaceScope workspace,
                                                                List<GraphRelationIdentity> input) {
        List<GraphRelationIdentity> result = new ArrayList<>(input);
        if (result.stream().anyMatch(identity -> identity == null)) {
            throw new IllegalArgumentException("Graph reconciliation contains a null relation identity");
        }
        result.sort(java.util.Comparator.comparing(GraphRelationIdentity::stableId));
        for (int i = 0; i < result.size(); i++) {
            if (!workspace.equals(result.get(i).workspace())
                    || (i > 0 && result.get(i - 1).equals(result.get(i)))) {
                throw new IllegalArgumentException("Graph reconciliation contains invalid relation identity");
            }
        }
        return List.copyOf(result);
    }
}
