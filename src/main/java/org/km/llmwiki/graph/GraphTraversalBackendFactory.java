package org.km.llmwiki.graph;

import java.util.Optional;

/** Lazily opens a provider-neutral read session without exposing the lifecycle/write port. */
public interface GraphTraversalBackendFactory {

    GraphProjectionVersion projectionVersion();

    Optional<GraphTraversalBackend> openTraversal(GraphWorkspaceScope workspace);
}
