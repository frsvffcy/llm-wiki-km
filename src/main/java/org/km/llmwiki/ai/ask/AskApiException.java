package org.km.llmwiki.ai.ask;

import java.util.Objects;

/** Internal signal used to map a typed Ask failure to a stable REST error envelope. */
public final class AskApiException extends RuntimeException {

    private final AskFailureType failureType;

    public AskApiException(AskFailureType failureType) {
        super(Objects.requireNonNull(failureType, "failureType must not be null").publicCode());
        this.failureType = failureType;
    }

    public AskFailureType failureType() {
        return failureType;
    }
}
