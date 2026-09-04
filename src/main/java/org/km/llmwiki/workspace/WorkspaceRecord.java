package org.km.llmwiki.workspace;

public record WorkspaceRecord(
        String name,
        String rootPath,
        String inboxPath,
        String archivePath,
        String vaultPath,
        String dataPath,
        String configPath,
        String status,
        String createdAt,
        String updatedAt) {
}
