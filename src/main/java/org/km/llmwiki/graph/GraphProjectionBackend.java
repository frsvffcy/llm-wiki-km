package org.km.llmwiki.graph;

/** One workspace-scoped session for a replaceable, disposable Graph projection backend. */
public interface GraphProjectionBackend extends AutoCloseable {

    GraphProjectionSnapshot rebuild(GraphProjectionInput input, GraphProjectionSnapshot target);

    GraphProjectionBackendProof readProof(GraphWorkspaceScope workspace);

    GraphProjectionWriteResult clearWorkspace(GraphWorkspaceScope workspace,
                                               GraphProjectionSnapshot expectedCurrent);

    @Override
    void close();
}
