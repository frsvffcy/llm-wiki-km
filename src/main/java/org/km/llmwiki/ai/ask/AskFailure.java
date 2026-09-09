package org.km.llmwiki.ai.ask;

import org.km.llmwiki.rag.RetrievalUnavailableException;
import org.km.llmwiki.web.DiagnosticRedaction;

import java.util.Objects;
import java.util.Optional;

/** Bounded, non-secret failure detail for an Ask result. */
public record AskFailure(
        AskFailureType type,
        String diagnostic,
        Optional<RetrievalUnavailableException.Dependency> retrievalDependency
) {

    public static final int MAX_DIAGNOSTIC_LENGTH = 160;

    public AskFailure {
        type = Objects.requireNonNull(type, "failure type must not be null");
        diagnostic = sanitize(diagnostic);
        retrievalDependency = retrievalDependency == null
                ? Optional.empty() : retrievalDependency;
        if (isRetrievalFailure(type)
                && retrievalDependency.isEmpty()) {
            throw new IllegalArgumentException(
                    "retrieval unavailable failures require a dependency");
        }
        if (!isRetrievalFailure(type)
                && retrievalDependency.isPresent()) {
            throw new IllegalArgumentException(
                    "provider failures must not contain a retrieval dependency");
        }
    }

    public AskFailure(AskFailureType type, String diagnostic) {
        this(type, diagnostic, Optional.empty());
    }

    private static boolean isRetrievalFailure(AskFailureType type) {
        return type == AskFailureType.RETRIEVAL_UNAVAILABLE
                || type == AskFailureType.RETRIEVAL_VECTOR_UNAVAILABLE;
    }

    private static String sanitize(String value) {
        return DiagnosticRedaction.sanitize(value, "", MAX_DIAGNOSTIC_LENGTH);
    }
}
