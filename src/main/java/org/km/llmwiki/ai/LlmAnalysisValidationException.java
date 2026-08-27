package org.km.llmwiki.ai;

public final class LlmAnalysisValidationException extends LlmClientException {

    public LlmAnalysisValidationException(String message) {
        super(LlmFailureType.VALIDATION, message);
    }

    public LlmAnalysisValidationException(String message, Throwable cause) {
        super(LlmFailureType.VALIDATION, message, cause);
    }
}
