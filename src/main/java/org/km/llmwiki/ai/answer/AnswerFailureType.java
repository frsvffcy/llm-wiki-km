package org.km.llmwiki.ai.answer;

/** Stable failure categories used by Ask orchestration and API mapping. */
public enum AnswerFailureType {
    CONFIGURATION_UNAVAILABLE_OR_DISABLED(false, "ANSWER_PROVIDER_NOT_CONFIGURED"),
    AUTHENTICATION_OR_AUTHORIZATION(false, "ANSWER_PROVIDER_AUTHENTICATION_FAILED"),
    RATE_LIMIT_OR_QUOTA(true, "ANSWER_PROVIDER_RATE_LIMITED"),
    TIMEOUT_OR_NETWORK_UNAVAILABLE(true, "ANSWER_PROVIDER_UNAVAILABLE"),
    PROVIDER_SERVER_FAILURE(true, "ANSWER_PROVIDER_SERVER_FAILURE"),
    INVALID_PROVIDER_RESPONSE(false, "ANSWER_PROVIDER_INVALID_RESPONSE"),
    LOCAL_VALIDATION(false, "ANSWER_REQUEST_REJECTED");

    private final boolean retryable;
    private final String publicCode;

    AnswerFailureType(boolean retryable, String publicCode) {
        this.retryable = retryable;
        this.publicCode = publicCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public String publicCode() {
        return publicCode;
    }
}
