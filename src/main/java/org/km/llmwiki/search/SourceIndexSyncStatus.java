package org.km.llmwiki.search;

/** Result of one workspace-scoped Source Chunk indexing attempt. */
public enum SourceIndexSyncStatus {
    SYNCED,
    INELIGIBLE,
    INDEX_PENDING,
    NOT_FOUND
}
