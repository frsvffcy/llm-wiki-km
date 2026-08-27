package org.km.llmwiki.ai;

import java.util.Objects;

/** Provider provenance attached to every accepted LLM result. */
public record LlmProviderMetadata(String provider, String model, String contractVersion) {

    public LlmProviderMetadata {
        provider = required(provider, "provider");
        model = required(model, "model");
        contractVersion = required(contractVersion, "contractVersion");
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
