package org.km.llmwiki.ai.embedding;

/** Non-secret provider and model identity authoritative to the adapter/configuration boundary. */
public record EmbeddingProviderMetadata(String provider, String model) {

    public EmbeddingProviderMetadata {
        provider = requireIdentifier(provider, "provider");
        model = requireIdentifier(model, "model");
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " is invalid or blank");
        }
        return value;
    }
}
