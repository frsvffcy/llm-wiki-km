package org.km.llmwiki.graph;

/** Provider-neutral comparison of SQLite authority and disposable backend proof. */
public enum GraphProjectionVerificationStatus {
    DISABLED,
    NOT_CONFIGURED,
    READY,
    NOT_READY,
    BUILDING,
    REPAIRING,
    CLEARING,
    STALE,
    BACKEND_UNAVAILABLE,
    PROJECTION_INCOMPATIBLE,
    REPAIR_REQUIRED
}
