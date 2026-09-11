package org.km.llmwiki.mcp;

/**
 * Validation failure for one MCP tool invocation. Extends {@link IllegalArgumentException} so
 * the existing tool boundary maps it to a typed {@code INVALID_REQUEST} without raw JSON,
 * paths, or exception chains ever reaching the client.
 */
public final class McpToolInputException extends IllegalArgumentException {

    public McpToolInputException(String safeMessage) {
        super(safeMessage);
    }
}
