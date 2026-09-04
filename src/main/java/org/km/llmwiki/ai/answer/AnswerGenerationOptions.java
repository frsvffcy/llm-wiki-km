package org.km.llmwiki.ai.answer;

/** Application-owned output bound; provider-specific token knobs belong in adapters. */
public record AnswerGenerationOptions(int maxOutputCodePoints) {

    public static final int DEFAULT_MAX_OUTPUT_CODE_POINTS = 4_000;
    public static final int MAX_ALLOWED_OUTPUT_CODE_POINTS = 16_000;

    public AnswerGenerationOptions {
        if (maxOutputCodePoints < 1 || maxOutputCodePoints > MAX_ALLOWED_OUTPUT_CODE_POINTS) {
            throw new IllegalArgumentException("maxOutputCodePoints must be between 1 and "
                    + MAX_ALLOWED_OUTPUT_CODE_POINTS);
        }
    }

    public static AnswerGenerationOptions defaults() {
        return new AnswerGenerationOptions(DEFAULT_MAX_OUTPUT_CODE_POINTS);
    }
}
