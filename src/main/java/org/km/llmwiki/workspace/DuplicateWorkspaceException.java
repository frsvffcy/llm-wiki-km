package org.km.llmwiki.workspace;

public class DuplicateWorkspaceException extends RuntimeException {

    private final Long existingWorkspaceId;

    public DuplicateWorkspaceException(String rootPath, Long existingWorkspaceId) {
        super("A workspace already exists for root path: " + rootPath);
        this.existingWorkspaceId = existingWorkspaceId;
    }

    public Long getExistingWorkspaceId() {
        return existingWorkspaceId;
    }
}
