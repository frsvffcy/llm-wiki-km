package org.km.llmwiki.graph;

/**
 * Application-owned operational port for the Graph projection: readiness query, explicit
 * canonical rebuild, and explicit repair over the SQLite-authoritative lifecycle. Implementations
 * own canonical input assembly and lifecycle semantics; callers (such as the operational REST
 * boundary) never touch a projection backend directly.
 */
public interface GraphProjectionOperations {

    GraphProjectionVerification readiness(long workspaceId);

    GraphProjectionVerification rebuild(long workspaceId);

    GraphProjectionVerification repair(long workspaceId);
}
