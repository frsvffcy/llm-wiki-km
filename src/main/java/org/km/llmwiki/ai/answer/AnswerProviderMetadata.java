package org.km.llmwiki.ai.answer;

/** Non-secret provider and model identity returned with an answer. */
public record AnswerProviderMetadata(String provider, String model) {

    public AnswerProviderMetadata {
        provider = requireIdentifier(provider, "provider");
        model = requireIdentifier(model, "model");
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > 128 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " is invalid or too long");
        }
        return value;
    }
}
