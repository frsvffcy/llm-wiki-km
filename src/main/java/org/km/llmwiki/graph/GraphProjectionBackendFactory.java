package org.km.llmwiki.graph;

import java.util.Optional;

/** Lazily opens a trusted, workspace-derived projection session. */
public interface GraphProjectionBackendFactory extends AutoCloseable {

    String provider();

    GraphProjectionVersion projectionVersion();

    GraphProjectionBackend openForWrite(GraphWorkspaceScope workspace);

    Optional<GraphProjectionBackend> openExisting(GraphWorkspaceScope workspace);

    @Override
    void close();
}
