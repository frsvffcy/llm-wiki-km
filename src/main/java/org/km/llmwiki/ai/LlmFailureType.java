package org.km.llmwiki.ai;

public enum LlmFailureType {
    VALIDATION(AnalysisFailureCode.CONTRACT_VALIDATION_FAILED),
    MALFORMED_JSON(AnalysisFailureCode.MALFORMED_JSON),
    UNKNOWN_ENUM(AnalysisFailureCode.UNKNOWN_ENUM),
    PROVIDER_UNAVAILABLE(AnalysisFailureCode.PROVIDER_UNAVAILABLE),
    PROVIDER_TIMEOUT(AnalysisFailureCode.PROVIDER_TIMEOUT);

    private final AnalysisFailureCode errorCode;

    LlmFailureType(AnalysisFailureCode errorCode) {
        this.errorCode = errorCode;
    }

    public AnalysisFailureCode errorCode() {
        return errorCode;
    }
}
