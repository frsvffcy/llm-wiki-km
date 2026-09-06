package org.km.llmwiki.graph;

/** SQLite-authoritative lifecycle state; backend presence alone never implies readiness. */
public enum GraphProjectionReadinessStatus {
    DISABLED,
    NOT_CONFIGURED,
    NOT_READY,
    BUILDING,
    REPAIRING,
    CLEARING,
    READY,
    FAILED,
    DEGRADED
}
