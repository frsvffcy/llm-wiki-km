package org.km.llmwiki.wiki;

/** Lifecycle states for indexed Wiki pages. Only PUBLISHED pages are valid MERGE targets. */
public enum PageStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED,
    DELETED
}
