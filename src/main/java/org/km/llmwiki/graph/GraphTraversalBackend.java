package org.km.llmwiki.graph;

/** Read-only workspace session for one replaceable Graph projection backend. */
public interface GraphTraversalBackend extends GraphTraversalReader, AutoCloseable {

    GraphProjectionBackendProof readProof(GraphWorkspaceScope workspace);

    @Override
    void close();
}
