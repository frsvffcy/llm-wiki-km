package org.km.llmwiki.graph;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Immutable provider-neutral request for one exact projection snapshot. */
public record GraphTraversalQuery(GraphWorkspaceScope workspace,
                                  List<GraphEntityIdentity> seeds,
                                  Set<GraphRelationType> allowedRelationTypes,
                                  GraphTraversalBounds bounds,
                                  GraphTraversalOrdering ordering,
                                  GraphProjectionSnapshot expectedSnapshot) {

    public static final int HARD_MAX_SEEDS = 16;

    public GraphTraversalQuery {
        if (workspace == null || seeds == null || allowedRelationTypes == null || bounds == null
                || ordering == null || expectedSnapshot == null) {
            throw new IllegalArgumentException("Graph traversal query is incomplete");
        }
        if (!workspace.equals(expectedSnapshot.workspace())) {
            throw new GraphProjectionException(GraphProjectionFailureType.CROSS_WORKSPACE);
        }
        if (seeds.isEmpty() || seeds.size() > HARD_MAX_SEEDS || allowedRelationTypes.isEmpty()
                || seeds.size() > bounds.maxVisitedNodes()) {
            throw new GraphProjectionException(
                    GraphProjectionFailureType.INVALID_TRAVERSAL_BOUNDS);
        }
        LinkedHashMap<String, GraphEntityIdentity> normalizedSeeds = new LinkedHashMap<>();
        seeds.stream().sorted(Comparator.comparing(GraphEntityIdentity::stableId))
                .forEach(seed -> {
                    if (!workspace.equals(seed.workspace())) {
                        throw new GraphProjectionException(
                                GraphProjectionFailureType.CROSS_WORKSPACE);
                    }
                    normalizedSeeds.putIfAbsent(seed.stableId(), seed);
                });
        if (normalizedSeeds.size() != seeds.size()) {
            throw new GraphProjectionException(
                    GraphProjectionFailureType.INVALID_TRAVERSAL_BOUNDS);
        }
        seeds = List.copyOf(normalizedSeeds.values());
        if (allowedRelationTypes.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Graph traversal relation types are incomplete");
        }
        allowedRelationTypes = Set.copyOf(allowedRelationTypes);
    }
}
