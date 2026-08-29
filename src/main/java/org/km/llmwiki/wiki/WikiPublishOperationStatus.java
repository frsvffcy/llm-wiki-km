package org.km.llmwiki.wiki;

/** Minimal CREATE publish saga states; STORY-407 may extend the recovery taxonomy. */
public enum WikiPublishOperationStatus {
    PREPARED,
    FILE_COMMITTED,
    COMPLETED,
    ROLLED_BACK,
    RECONCILIATION_REQUIRED
}
