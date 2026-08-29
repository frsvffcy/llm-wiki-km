package org.km.llmwiki.search;

/** Result returned by one Published Wiki indexing attempt. */
public enum WikiIndexSyncStatus {
    SYNCED,
    INDEX_PENDING,
    DRIFT,
    NOT_FOUND
}
