package org.km.llmwiki.ai.answer;

/**
 * Typed projection failure. The message is an operator-safe stable summary; raw exception
 * details stay in server-side logs and never enter this exception or any public projection.
 */
public final class AnswerContextProjectionException extends RuntimeException {

    private final ContextProjectionFailureType failureType;

    public AnswerContextProjectionException(ContextProjectionFailureType failureType,
                                            String safeMessage) {
        super(safeMessage);
        if (failureType == null) {
            throw new IllegalArgumentException("projection failure type must not be null");
        }
        this.failureType = failureType;
    }

    public ContextProjectionFailureType failureType() {
        return failureType;
    }
}
