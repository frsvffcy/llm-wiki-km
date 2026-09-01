package org.km.llmwiki.ai.answer;

import java.util.Objects;

/** Typed answer-client failure; infrastructure errors must not become empty answers. */
public final class AnswerClientException extends RuntimeException {

    private final AnswerFailure failure;

    public AnswerClientException(AnswerFailure failure) {
        super(messageFor(Objects.requireNonNull(failure, "failure must not be null")));
        this.failure = failure;
    }

    public AnswerClientException(AnswerFailureType type, String diagnostic) {
        this(new AnswerFailure(type, diagnostic));
    }

    public AnswerFailure failure() {
        return failure;
    }

    public AnswerFailureType failureType() {
        return failure.type();
    }

    public String publicCode() {
        return failure.publicCode();
    }

    private static String messageFor(AnswerFailure failure) {
        if (failure.diagnostic().isBlank()) {
            return "Answer generation failed: " + failure.publicCode();
        }
        return "Answer generation failed: " + failure.publicCode() + " ("
                + failure.diagnostic() + ")";
    }
}
