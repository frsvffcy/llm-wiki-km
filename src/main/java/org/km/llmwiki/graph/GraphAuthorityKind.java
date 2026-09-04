package org.km.llmwiki.graph;

/** Canonical authority families that may provide Graph provenance. */
public enum GraphAuthorityKind {
    WIKI_PAGE("wiki-page"),
    SOURCE_DOCUMENT("source-document"),
    SOURCE_CHUNK("source-chunk"),
    CANONICAL_METADATA("canonical-metadata");

    private final String wireValue;

    GraphAuthorityKind(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static GraphAuthorityKind fromWireValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Graph authority kind is required");
        }
        for (GraphAuthorityKind kind : values()) {
            if (kind.wireValue.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown Graph authority kind");
    }
}
