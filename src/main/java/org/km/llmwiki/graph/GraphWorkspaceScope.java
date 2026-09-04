package org.km.llmwiki.graph;

/** Explicit workspace boundary carried by every Graph identity and projection operation. */
public record GraphWorkspaceScope(long id) {

    public GraphWorkspaceScope {
        if (id <= 0) {
            throw new IllegalArgumentException("Graph workspace id must be positive");
        }
    }
}
