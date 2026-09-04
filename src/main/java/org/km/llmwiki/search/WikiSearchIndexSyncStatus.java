package org.km.llmwiki.search;

/** Repair state for the rebuildable Published Wiki FTS projection. */
public enum WikiSearchIndexSyncStatus {
    SYNCED,
    INDEX_PENDING,
    DRIFT
}
