package org.km.llmwiki.ai;

public final class LlmAnalysisValidationException extends LlmClientException {

    public LlmAnalysisValidationException(String message) {
        super(LlmFailureType.VALIDATION, message);
    }

    public LlmAnalysisValidationException(String message, Throwable cause) {
        super(LlmFailureType.VALIDATION, message, cause);
    }

    public LlmAnalysisValidationException(LlmFailureType failureType, String message) {
        super(failureType, message);
        if (failureType != LlmFailureType.MALFORMED_JSON && failureType != LlmFailureType.UNKNOWN_ENUM
                && failureType != LlmFailureType.VALIDATION) {
            throw new IllegalArgumentException("validation exception must use a validation failure type");
        }
    }

    public LlmAnalysisValidationException(LlmFailureType failureType, String message, Throwable cause) {
        super(failureType, message, cause);
        if (failureType != LlmFailureType.MALFORMED_JSON && failureType != LlmFailureType.UNKNOWN_ENUM
                && failureType != LlmFailureType.VALIDATION) {
            throw new IllegalArgumentException("validation exception must use a validation failure type");
        }
    }

    public LlmAnalysisValidationException(AnalysisFailureCode errorCode, String message) {
        super(LlmFailureType.VALIDATION, message);
        if (errorCode != AnalysisFailureCode.ILLEGAL_EVIDENCE
                && errorCode != AnalysisFailureCode.INSUFFICIENT_EVIDENCE) {
            throw new IllegalArgumentException("unsupported validation error code");
        }
        this.errorCode = errorCode;
    }

    private AnalysisFailureCode errorCode;

    @Override
    public AnalysisFailureCode errorCode() {
        return errorCode == null ? super.errorCode() : errorCode;
    }
}
