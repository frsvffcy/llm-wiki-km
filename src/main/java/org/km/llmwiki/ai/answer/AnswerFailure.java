package org.km.llmwiki.ai.answer;

import org.km.llmwiki.web.DiagnosticRedaction;

import java.util.Objects;

/**
 * Typed, bounded, safe diagnostic for answer generation failure.
 *
 * <p>Diagnostics are intentionally not raw provider exceptions. Callers should pass a short
 * category detail only; common credential forms are redacted as a final safety measure.
 */
public record AnswerFailure(AnswerFailureType type, String diagnostic) {

    public static final int MAX_DIAGNOSTIC_LENGTH = 160;

    public AnswerFailure {
        type = Objects.requireNonNull(type, "failure type must not be null");
        diagnostic = sanitize(diagnostic);
    }

    private static String sanitize(String value) {
        return DiagnosticRedaction.sanitize(value, "", MAX_DIAGNOSTIC_LENGTH);
    }

    public boolean retryable() {
        return type.retryable();
    }

    public String publicCode() {
        return type.publicCode();
    }
}
