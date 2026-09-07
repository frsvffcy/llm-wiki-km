package org.km.llmwiki.graph;

/** Read-only application port for serving-time projection currentness checks. */
@FunctionalInterface
public interface GraphProjectionReadinessReader {

    GraphProjectionVerification readiness(GraphWorkspaceScope workspace);
}
