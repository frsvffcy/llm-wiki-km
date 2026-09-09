package org.km.llmwiki.ai.embedding;

import org.km.llmwiki.web.DiagnosticRedaction;

import java.util.Objects;

/** Typed, bounded, secret-safe embedding failure detail. */
public record EmbeddingFailure(EmbeddingFailureType type, String diagnostic) {

    public static final int MAX_DIAGNOSTIC_LENGTH = 160;
    public EmbeddingFailure {
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
