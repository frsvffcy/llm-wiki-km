package org.km.llmwiki.ai.ask;

import org.km.llmwiki.web.DiagnosticRedaction;

import java.util.Objects;

/**
 * Internal signal used to map a typed Ask failure to a stable REST error envelope.
 *
 * <p>The exception message remains the public error code because MCP also consumes it.
 * The operator diagnostic is a separate, redacted and bounded server-only channel.
 */
public final class AskApiException extends RuntimeException {

    private final AskFailureType failureType;
    private final String operatorDiagnostic;

    public AskApiException(AskFailureType failureType) {
        this(failureType, "");
    }

    public AskApiException(AskFailureType failureType, String operatorDiagnostic) {
        super(Objects.requireNonNull(failureType, "failureType must not be null").publicCode());
        this.failureType = failureType;
        this.operatorDiagnostic = DiagnosticRedaction.sanitize(operatorDiagnostic, "",
                AskFailure.MAX_DIAGNOSTIC_LENGTH);
    }

    public AskFailureType failureType() {
        return failureType;
    }

    public String operatorDiagnostic() {
        return operatorDiagnostic;
    }
}
