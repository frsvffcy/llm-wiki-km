package org.km.llmwiki.graph;

/** Provider-neutral outcome of a generation-guarded stale cleanup. */
public enum GraphProjectionCleanupStatus {
    /** The expected snapshot was current and at least one stale row was removed or superseded. */
    APPLIED,
    /** The expected snapshot was current, but cleanup had nothing left to change. */
    NO_OP,
    /** A newer generation was current; the cleanup was rejected without mutation. */
    SUPERSEDED
}
