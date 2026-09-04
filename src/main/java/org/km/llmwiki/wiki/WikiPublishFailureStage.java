package org.km.llmwiki.wiki;

public enum WikiPublishFailureStage {
    VALIDATION,
    TARGET_CHECK,
    OPERATION_RESERVATION,
    FILESYSTEM,
    DATABASE_FINALIZATION,
    RECONCILIATION
}
