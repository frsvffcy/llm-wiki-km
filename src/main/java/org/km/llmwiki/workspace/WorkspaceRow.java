package org.km.llmwiki.workspace;

import java.util.List;

public record WorkspaceRow(
        Long id,
        String name,
        String rootPath,
        String inboxPath,
        String archivePath,
        String vaultPath,
        String dataPath,
        String configPath,
        String status,
        String createdAt,
        String updatedAt,
        String lastOpenedAt) {

    public WorkspaceResponse toResponse() {
        return new WorkspaceResponse(id, name, rootPath, inboxPath, archivePath,
                vaultPath, dataPath, configPath, status, createdAt, updatedAt);
    }
}
