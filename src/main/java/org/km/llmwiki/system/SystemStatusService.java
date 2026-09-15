package org.km.llmwiki.system;

import org.km.llmwiki.persistence.SQLiteConnectionProbe;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Service
public class SystemStatusService {

    private final SQLiteConnectionProbe connectionProbe;
    private final WorkspaceService workspaceService;
    private final ApplicationVersion applicationVersion;

    public SystemStatusService(SQLiteConnectionProbe connectionProbe, WorkspaceService workspaceService,
            ApplicationVersion applicationVersion) {
        this.connectionProbe = connectionProbe;
        this.workspaceService = workspaceService;
        this.applicationVersion = applicationVersion;
    }

    public SystemStatusResponse getStatus() {
        boolean databaseReady = isDatabaseReady();
        if (!databaseReady) {
            return new SystemStatusResponse("ERROR", version(), null, null, "ERROR");
        }

        Optional<WorkspaceResponse> active = findActiveWorkspaceSafely();
        if (active.isEmpty()) {
            return new SystemStatusResponse("NOT_INITIALIZED", version(), null, null, "READY");
        }

        WorkspaceResponse workspace = active.get();
        String overall = Files.isDirectory(Path.of(workspace.rootPath())) ? "READY" : "DEGRADED";
        return new SystemStatusResponse(overall, version(), workspace.id(), workspace.name(), "READY");
    }

    private boolean isDatabaseReady() {
        try {
            return connectionProbe.isReachable();
        } catch (DataAccessException exception) {
            return false;
        }
    }

    private Optional<WorkspaceResponse> findActiveWorkspaceSafely() {
        try {
            return workspaceService.findActiveWithoutValidation();
        } catch (DataAccessException exception) {
            return Optional.empty();
        }
    }

    private String version() {
        // Refs #456 R1: single generated authority, never a Java literal.
        return applicationVersion.version();
    }
}
