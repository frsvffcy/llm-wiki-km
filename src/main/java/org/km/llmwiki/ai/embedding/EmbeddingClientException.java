package org.km.llmwiki.ai.embedding;

import java.util.Objects;

/** Typed embedding-client failure; infrastructure errors must not become empty vectors. */
public final class EmbeddingClientException extends RuntimeException {

    private final EmbeddingFailure failure;

    public EmbeddingClientException(EmbeddingFailure failure) {
        super(messageFor(Objects.requireNonNull(failure, "failure must not be null")));
        this.failure = failure;
    }

    public EmbeddingClientException(EmbeddingFailureType type, String diagnostic) {
        this(new EmbeddingFailure(type, diagnostic));
    }

    public EmbeddingFailure failure() {
        return failure;
    }

    public EmbeddingFailureType failureType() {
        return failure.type();
    }

    public String publicCode() {
        return failure.publicCode();
    }

    private static String messageFor(EmbeddingFailure failure) {
        return failure.diagnostic().isBlank()
                ? "Embedding generation failed: " + failure.publicCode()
                : "Embedding generation failed: " + failure.publicCode()
                        + " (" + failure.diagnostic() + ")";
    }
}
