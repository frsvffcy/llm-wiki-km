package org.km.llmwiki.persistence.graph.arcadedb;

import org.km.llmwiki.graph.GraphProjectionBackend;
import org.km.llmwiki.graph.GraphProjectionBackendProof;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionSnapshot;
import org.km.llmwiki.graph.GraphProjectionWriteResult;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.graph.GraphTraversalQuery;
import org.km.llmwiki.graph.GraphTraversalResult;
import org.km.llmwiki.graph.GraphTraversalBackend;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded, workspace-owned embedded ArcadeDB session. */
public final class ArcadeDbGraphProjectionBackend
        implements GraphProjectionBackend, GraphTraversalBackend {

    private final GraphWorkspaceScope workspace;
    private final ArcadeDbGraphProjectionWriter writer;
    private final Runnable closeCallback;
    private final AtomicBoolean closed = new AtomicBoolean();

    ArcadeDbGraphProjectionBackend(GraphWorkspaceScope workspace, Path path,
                                    boolean createIfMissing, Runnable closeCallback) {
        if (workspace == null || path == null || closeCallback == null) {
            throw new IllegalArgumentException("ArcadeDB backend ownership is incomplete");
        }
        this.workspace = workspace;
        this.closeCallback = closeCallback;
        this.writer = new ArcadeDbGraphProjectionWriter(path, createIfMissing);
    }

    @Override
    public GraphProjectionSnapshot rebuild(GraphProjectionInput input,
                                           GraphProjectionSnapshot target) {
        requireWorkspace(input == null ? null : input.workspace());
        requireWorkspace(target == null ? null : target.workspace());
        if (!input.projectionVersion().equals(target.projectionVersion())
                || !input.sourceFingerprint().equals(target.sourceFingerprint())) {
            throw new GraphProjectionException(GraphProjectionFailureType.INVALID_PROJECTION_INPUT);
        }
        return new ArcadeDbGraphProjectionRebuilder(writer, target).rebuild(input);
    }

    @Override
    public GraphProjectionBackendProof readProof(GraphWorkspaceScope requestedWorkspace) {
        requireWorkspace(requestedWorkspace);
        return writer.readProof(requestedWorkspace);
    }

    @Override
    public GraphProjectionWriteResult clearWorkspace(GraphWorkspaceScope requestedWorkspace,
                                                      GraphProjectionSnapshot expectedCurrent) {
        requireWorkspace(requestedWorkspace);
        return writer.clearWorkspace(requestedWorkspace, expectedCurrent);
    }

    @Override
    public GraphTraversalResult traverse(GraphTraversalQuery query) {
        requireWorkspace(query == null ? null : query.workspace());
        return writer.traverse(query);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            writer.close();
        } finally {
            closeCallback.run();
        }
    }

    private void requireWorkspace(GraphWorkspaceScope requestedWorkspace) {
        if (requestedWorkspace == null || !workspace.equals(requestedWorkspace)) {
            throw new GraphProjectionException(GraphProjectionFailureType.CROSS_WORKSPACE);
        }
    }
}
