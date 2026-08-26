package com.frsvffcy.llmwiki.workspace;

public record WorkspaceResponse(
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
        String updatedAt) {
}
