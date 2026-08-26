package org.km.llmwiki.workspace;

public class WorkspaceNotFoundException extends RuntimeException {

    public WorkspaceNotFoundException(long workspaceId) {
        super("Workspace not found: " + workspaceId);
    }
}
