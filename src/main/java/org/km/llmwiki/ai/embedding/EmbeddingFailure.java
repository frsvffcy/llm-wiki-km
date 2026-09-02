package org.km.llmwiki.ai.embedding;

import java.util.Objects;
import java.util.regex.Pattern;

/** Typed, bounded, secret-safe embedding failure detail. */
public record EmbeddingFailure(EmbeddingFailureType type, String diagnostic) {

    public static final int MAX_DIAGNOSTIC_LENGTH = 160;
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(api[-_ ]?key|authorization|token|secret|password)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bbearer\\s+[^\\s,;]+");
    private static final Pattern SECRET_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b");

    public EmbeddingFailure {
        type = Objects.requireNonNull(type, "failure type must not be null");
        diagnostic = sanitize(diagnostic);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String bounded = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        bounded = SECRET_ASSIGNMENT.matcher(bounded).replaceAll("$1=[REDACTED]");
        bounded = BEARER_TOKEN.matcher(bounded).replaceAll("Bearer [REDACTED]");
        bounded = SECRET_KEY.matcher(bounded).replaceAll("[REDACTED]");
        return bounded.length() <= MAX_DIAGNOSTIC_LENGTH
                ? bounded : bounded.substring(0, MAX_DIAGNOSTIC_LENGTH);
    }

    public boolean retryable() {
        return type.retryable();
    }

    public String publicCode() {
        return type.publicCode();
    }
}
