package org.km.llmwiki.ai.ask;

import org.km.llmwiki.rag.RetrievalUnavailableException;

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
        if (value == null || value.isBlank()) {
            return "";
        }
        String bounded = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        bounded = bounded.replaceAll("(?i)(api[-_ ]?key|authorization|token|secret|password)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+",
                "$1=[REDACTED]");
        bounded = bounded.replaceAll("(?i)\\bbearer\\s+[^\\s,;]+", "Bearer [REDACTED]");
        bounded = bounded.replaceAll("\\bsk-[A-Za-z0-9_-]{8,}\\b", "[REDACTED]");
        return bounded.length() <= MAX_DIAGNOSTIC_LENGTH
                ? bounded : bounded.substring(0, MAX_DIAGNOSTIC_LENGTH);
    }
}
