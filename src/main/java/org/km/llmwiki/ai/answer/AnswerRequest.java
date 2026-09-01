package org.km.llmwiki.ai.answer;

/** Immutable, provider-neutral input for one answer generation request. */
public record AnswerRequest(
        String question,
        AnswerContext context,
        AnswerGenerationOptions options
) {

    public AnswerRequest {
        question = requireNonBlank(question, "question");
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        if (question.codePointCount(0, question.length()) > 4_000) {
            throw new IllegalArgumentException("question must not exceed 4000 Unicode code points");
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
