package org.km.llmwiki.ai;

/** Stable, credential-safe error codes for analysis failures and their retry policy. */
public enum AnalysisFailureCode {
    MALFORMED_JSON(false),
    CONTRACT_VALIDATION_FAILED(false),
    UNKNOWN_ENUM(false),
    ILLEGAL_EVIDENCE(false),
    INSUFFICIENT_EVIDENCE(false),
    PROMPT_CONFIGURATION_FAILED(false),
    PROMPT_TEMPLATE_NOT_FOUND(false),
    PROMPT_TEMPLATE_INVALID(false),
    PROMPT_VARIABLE_MISSING(false),
    ANALYSIS_SETTING_INVALID(false),
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

    /**
     * Preserves the typed {@link PromptLoadErrorCode} subtype instead of flattening every
     * prompt failure into the umbrella {@code PROMPT_CONFIGURATION_FAILED} (issue #448).
     * The umbrella code remains only as a defensive fallback and is no longer written for
     * known prompt failures.
     */
    public static AnalysisFailureCode fromPromptError(PromptLoadErrorCode errorCode) {
        if (errorCode == null) {
            return PROMPT_CONFIGURATION_FAILED;
        }
        return switch (errorCode) {
            case PROMPT_TEMPLATE_NOT_FOUND -> PROMPT_TEMPLATE_NOT_FOUND;
            case PROMPT_TEMPLATE_INVALID -> PROMPT_TEMPLATE_INVALID;
            case PROMPT_VARIABLE_MISSING -> PROMPT_VARIABLE_MISSING;
            case ANALYSIS_SETTING_INVALID -> ANALYSIS_SETTING_INVALID;
        };
    }
}
