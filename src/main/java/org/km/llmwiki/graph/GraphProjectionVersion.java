package org.km.llmwiki.graph;

/** Version identity for the provider-neutral graph projection contract. */
public record GraphProjectionVersion(String value) {

    public GraphProjectionVersion {
        if (value == null || value.isBlank() || !value.matches("[a-z][a-z0-9._-]{0,31}")) {
            throw new IllegalArgumentException("Graph projection version is invalid");
        }
        value = value.trim();
    }

    public static GraphProjectionVersion initial() {
        return new GraphProjectionVersion("graph-projection-v1");
    }
}
