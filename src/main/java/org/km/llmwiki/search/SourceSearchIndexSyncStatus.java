package org.km.llmwiki.search;

/** Durable repair state for one document's rebuildable Source FTS projection. */
public enum SourceSearchIndexSyncStatus {
    SYNCED,
    INELIGIBLE,
    INDEX_PENDING
}
