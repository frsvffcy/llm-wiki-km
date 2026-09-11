package org.km.llmwiki.mcp;

import java.util.List;

/**
 * Bounded capability manifest for one MCP tool. Annotations are client hints; the server-side
 * adapter still enforces the real read-only boundary, workspace scope, and bounds. The
 * {@code askProviderEgressPossible} flag distinguishes "read-only" (no canonical mutation)
 * from "no network egress" — the ask tool never mutates canonical knowledge but may egress
 * bounded provider-bound context when a remote provider is configured.
 */
public record McpToolDescriptor(
        String name,
        String title,
        String description,
        boolean readOnly,
        boolean destructive,
        boolean idempotent,
        boolean activeWorkspaceScoped,
        boolean askProviderEgressPossible,
        String inputBound,
        List<String> inputSchema
) {

    public McpToolDescriptor {
        if (name == null || name.strip().isEmpty() || name.strip().length() > 64) {
            throw new IllegalArgumentException("mcp tool name is invalid");
        }
        name = name.strip();
        description = description == null ? "" : description.strip();
        inputBound = inputBound == null || inputBound.isBlank() ? "bounded" : inputBound.strip();
        inputSchema = List.copyOf(inputSchema == null ? List.of() : inputSchema);
    }
}
