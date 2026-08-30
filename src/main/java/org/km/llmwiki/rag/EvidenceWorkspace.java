package org.km.llmwiki.rag;

/** Workspace provenance attached to the bundle and every evidence item. */
public record EvidenceWorkspace(long id, String name) {

    public EvidenceWorkspace {
        if (id <= 0 || name == null || name.isBlank()) {
            throw new IllegalArgumentException("Evidence workspace provenance is incomplete");
        }
    }
}
