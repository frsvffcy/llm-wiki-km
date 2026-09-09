package org.km.llmwiki.source;

/** Minimal typed block kinds for a structure-preserving parse result. */
public enum ParsedBlockKind {
    HEADING,
    PARAGRAPH,
    TABLE,
    FIGURE,
    CAPTION
}
