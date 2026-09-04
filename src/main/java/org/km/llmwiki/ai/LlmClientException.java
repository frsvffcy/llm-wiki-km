package org.km.llmwiki.ai;

public class LlmClientException extends RuntimeException {

    private final LlmFailureType failureType;

    public LlmClientException(LlmFailureType failureType, String message) {
        super(message);
        this.failureType = failureType;
    }

    public LlmClientException(LlmFailureType failureType, String message, Throwable cause) {
        super(message, cause);
        this.failureType = failureType;
    }

    public LlmFailureType failureType() {
        return failureType;
    }

    public AnalysisFailureCode errorCode() {
        return failureType.errorCode();
    }
}
