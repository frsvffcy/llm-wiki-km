package org.km.llmwiki.graph;

/** Provider-neutral outcome of a generation-owned projection mutation or publication. */
public enum GraphProjectionWriteStatus {
    /** The proof was current or staged and the requested state changed. */
    APPLIED,
    /** The proof was accepted, but the requested state was already present. */
    NO_OP,
    /** A newer generation owns visibility; the operation made no mutation. */
    SUPERSEDED
}
