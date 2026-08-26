package org.km.llmwiki.system;

public record SystemStatusResponse(
        String status,
        String version,
        Long workspaceId,
        String workspaceName,
        String database) {
}
