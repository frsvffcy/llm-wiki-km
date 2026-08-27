package org.km.llmwiki.ai;

/** Stable, credential-safe error codes for analysis failures and their retry policy. */
public enum AnalysisFailureCode {
    MALFORMED_JSON(false),
    CONTRACT_VALIDATION_FAILED(false),
    UNKNOWN_ENUM(false),
    ILLEGAL_EVIDENCE(false),
    INSUFFICIENT_EVIDENCE(false),
    PROMPT_CONFIGURATION_FAILED(false),
    PROVIDER_UNAVAILABLE(true),
    PROVIDER_TIMEOUT(true),
    PERSISTENCE_FAILED(true),
    UNEXPECTED_FAILURE(false);

    private final boolean retryEligible;

    AnalysisFailureCode(boolean retryEligible) {
        this.retryEligible = retryEligible;
    }

    public boolean retryEligible() {
        return retryEligible;
    }
}
