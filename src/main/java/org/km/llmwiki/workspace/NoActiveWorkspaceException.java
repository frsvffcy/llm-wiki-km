package org.km.llmwiki.workspace;

public class NoActiveWorkspaceException extends RuntimeException {

    public NoActiveWorkspaceException() {
        super("No active workspace has been registered");
    }
}
