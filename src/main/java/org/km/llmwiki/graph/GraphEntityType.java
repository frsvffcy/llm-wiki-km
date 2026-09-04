package org.km.llmwiki.graph;

/** Provider-neutral entity kinds supported by the initial projection contract. */
public enum GraphEntityType {
    WIKI_PAGE(GraphAuthorityKind.WIKI_PAGE),
    SOURCE_DOCUMENT(GraphAuthorityKind.SOURCE_DOCUMENT),
    SOURCE_CHUNK(GraphAuthorityKind.SOURCE_CHUNK),
    CONCEPT(null);

    private final GraphAuthorityKind authorityKind;

    GraphEntityType(GraphAuthorityKind authorityKind) {
        this.authorityKind = authorityKind;
    }

    /** Returns the authority kind required for direct canonical entities, or {@code null} for derived entities. */
    public GraphAuthorityKind authorityKind() {
        return authorityKind;
    }
}
