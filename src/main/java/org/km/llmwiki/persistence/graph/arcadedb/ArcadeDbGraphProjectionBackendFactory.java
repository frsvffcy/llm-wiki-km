package org.km.llmwiki.persistence.graph.arcadedb;

import org.km.llmwiki.graph.GraphProjectionBackend;
import org.km.llmwiki.graph.GraphProjectionBackendFactory;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.graph.GraphWorkspaceScope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Derives every database path from trusted application configuration and a numeric workspace id.
 * One in-process session per workspace is allowed; ArcadeDB's own lock remains the cross-process
 * fail-closed boundary.
 */
public final class ArcadeDbGraphProjectionBackendFactory implements GraphProjectionBackendFactory {

    public static final String PROVIDER = "arcadedb";

    private final Path basePath;
    private final GraphProjectionVersion projectionVersion;
    private final Set<Long> activeWorkspaces = ConcurrentHashMap.newKeySet();
    private final Set<GraphProjectionBackend> sessions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object resourceLifecycleMonitor = new Object();
    private final BackendOpener backendOpener;

    public ArcadeDbGraphProjectionBackendFactory(Path basePath,
                                                  GraphProjectionVersion projectionVersion) {
        this(basePath, projectionVersion, ArcadeDbGraphProjectionBackend::new);
    }

    ArcadeDbGraphProjectionBackendFactory(Path basePath,
                                          GraphProjectionVersion projectionVersion,
                                          BackendOpener backendOpener) {
        if (basePath == null || projectionVersion == null || backendOpener == null) {
            throw new IllegalArgumentException("ArcadeDB factory configuration is incomplete");
        }
        this.basePath = basePath.toAbsolutePath().normalize();
        this.projectionVersion = projectionVersion;
        this.backendOpener = backendOpener;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public GraphProjectionVersion projectionVersion() {
        return projectionVersion;
    }

    @Override
    public GraphProjectionBackend openForWrite(GraphWorkspaceScope workspace) {
        return open(workspace, true);
    }

    @Override
    public Optional<GraphProjectionBackend> openExisting(GraphWorkspaceScope workspace) {
        Path path = workspacePath(workspace);
        if (!Files.isDirectory(path)) {
            return Optional.empty();
        }
        return Optional.of(open(workspace, false));
    }

    @Override
    public void close() {
        synchronized (resourceLifecycleMonitor) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            for (GraphProjectionBackend session : Set.copyOf(sessions)) {
                session.close();
            }
            sessions.clear();
            activeWorkspaces.clear();
        }
    }

    Path workspacePath(GraphWorkspaceScope workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("Graph workspace is required");
        }
        Path derived = basePath.resolve("workspace-" + workspace.id()).normalize();
        if (!derived.startsWith(basePath)) {
            throw new GraphProjectionException(GraphProjectionFailureType.CONFIGURATION_INVALID);
        }
        return derived;
    }

    private GraphProjectionBackend open(GraphWorkspaceScope workspace,
                                        boolean createIfMissing) {
        // This monitor only owns in-process resources during factory open/close. Durable
        // generation and operation ownership remain exclusively in the SQLite control plane.
        synchronized (resourceLifecycleMonitor) {
            if (closed.get()) {
                throw new GraphProjectionException(
                        GraphProjectionFailureType.CAPABILITY_UNAVAILABLE);
            }
            Path path = workspacePath(workspace);
            if (!activeWorkspaces.add(workspace.id())) {
                throw new GraphProjectionException(GraphProjectionFailureType.BACKEND_LOCKED);
            }
            GraphProjectionBackend[] holder = new GraphProjectionBackend[1];
            try {
                GraphProjectionBackend backend = backendOpener.open(workspace, path,
                        createIfMissing, () -> release(workspace.id(), holder[0]));
                holder[0] = backend;
                sessions.add(backend);
                return backend;
            } catch (RuntimeException failure) {
                activeWorkspaces.remove(workspace.id());
                throw failure;
            }
        }
    }

    private void release(long workspaceId, GraphProjectionBackend backend) {
        synchronized (resourceLifecycleMonitor) {
            if (backend != null) {
                sessions.remove(backend);
            }
            activeWorkspaces.remove(workspaceId);
        }
    }

    @FunctionalInterface
    interface BackendOpener {
        GraphProjectionBackend open(GraphWorkspaceScope workspace, Path path,
                                    boolean createIfMissing, Runnable closeCallback);
    }
}
