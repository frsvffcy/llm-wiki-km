package org.km.llmwiki.ai;

import java.util.Objects;

/**
 * Non-sensitive options for a document analysis request. Credentials intentionally have no
 * representation here and must be injected only by a provider implementation at runtime.
 */
public record AnalysisSettings(String provider, String model, int maximumEvidenceChunks) {

    public static final String DEFAULT_PROVIDER = "stub";
    public static final String DEFAULT_MODEL = "offline";
    public static final int DEFAULT_MAXIMUM_EVIDENCE_CHUNKS = 50;

    public AnalysisSettings {
        provider = require(provider, "provider");
        model = require(model, "model");
        if (maximumEvidenceChunks <= 0 || maximumEvidenceChunks > 200) {
            throw new IllegalArgumentException("maximumEvidenceChunks must be between 1 and 200");
        }
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
