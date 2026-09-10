package org.km.llmwiki.ai.answer;

/** Safe, provider-neutral status for optional provider-reported usage counters. */
public enum ProviderUsageStatus {
    /** No answer-provider call was attempted for this Ask execution. */
    NOT_ATTEMPTED,
    /** The provider returned at least one verified usage counter. */
    AVAILABLE,
    /** A provider call was attempted or completed without usable usage counters. */
    UNAVAILABLE
}
