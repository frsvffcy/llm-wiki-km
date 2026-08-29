package org.km.llmwiki.wiki;

/** Coarse operational failure boundary used for recovery and diagnostics. */
public enum WikiPublishFailureCategory {
    VALIDATION,
    CONFLICT,
    FILESYSTEM,
    DATABASE,
    RECONCILIATION
}
