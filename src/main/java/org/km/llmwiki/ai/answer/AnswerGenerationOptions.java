package org.km.llmwiki.ai.answer;

/** Minimum provider-neutral generation control; provider-specific knobs belong in adapters. */
public record AnswerGenerationOptions(int maxOutputCharacters) {

    public static final int DEFAULT_MAX_OUTPUT_CHARACTERS = 4_000;
    public static final int MAX_ALLOWED_OUTPUT_CHARACTERS = 16_000;

    public AnswerGenerationOptions {
        if (maxOutputCharacters < 1 || maxOutputCharacters > MAX_ALLOWED_OUTPUT_CHARACTERS) {
            throw new IllegalArgumentException("maxOutputCharacters must be between 1 and "
                    + MAX_ALLOWED_OUTPUT_CHARACTERS);
        }
    }

    public static AnswerGenerationOptions defaults() {
        return new AnswerGenerationOptions(DEFAULT_MAX_OUTPUT_CHARACTERS);
    }
}
