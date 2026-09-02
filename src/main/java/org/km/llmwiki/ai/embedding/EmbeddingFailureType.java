package org.km.llmwiki.ai.embedding;

/** Stable failure categories for embedding orchestration and future projection work. */
public enum EmbeddingFailureType {
    CONFIGURATION_UNAVAILABLE_OR_DISABLED(false, "EMBEDDING_PROVIDER_NOT_CONFIGURED"),
    AUTHENTICATION_OR_AUTHORIZATION(false, "EMBEDDING_PROVIDER_AUTHENTICATION_FAILED"),
    RATE_LIMIT_OR_QUOTA(true, "EMBEDDING_PROVIDER_RATE_LIMITED"),
    TIMEOUT_OR_NETWORK_UNAVAILABLE(true, "EMBEDDING_PROVIDER_UNAVAILABLE"),
    PROVIDER_SERVER_FAILURE(true, "EMBEDDING_PROVIDER_SERVER_FAILURE"),
    INVALID_PROVIDER_RESPONSE(false, "EMBEDDING_PROVIDER_INVALID_RESPONSE"),
    LOCAL_VALIDATION(false, "EMBEDDING_REQUEST_REJECTED");

    private final boolean retryable;
    private final String publicCode;

    EmbeddingFailureType(boolean retryable, String publicCode) {
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
