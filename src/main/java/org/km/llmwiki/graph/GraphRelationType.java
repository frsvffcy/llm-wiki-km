package org.km.llmwiki.graph;

/** Finite semantic relation vocabulary for the provider-neutral graph model. */
public enum GraphRelationType {
    LINKS_TO("links-to"),
    RELATED_TO("related-to"),
    DERIVED_FROM("derived-from"),
    MENTIONS("mentions"),
    CONTAINS("contains"),
    TAGGED_WITH("tagged-with");

    private final String wireValue;

    GraphRelationType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
