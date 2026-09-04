package org.km.llmwiki.workspace;

public record WorkspaceStatusResponse(WorkspaceResponse workspace,
                                     WorkspaceLayoutValidator.LayoutReport layout) {
}
