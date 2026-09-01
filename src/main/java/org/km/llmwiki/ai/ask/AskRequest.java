package org.km.llmwiki.ai.ask;

import org.km.llmwiki.ai.answer.AnswerContextBudget;
import org.km.llmwiki.ai.answer.AnswerGenerationOptions;
import org.km.llmwiki.rag.RetrievalMode;
import org.km.llmwiki.rag.RetrievalRequest;

/** Provider-neutral input for one bounded application-level ask operation. */
public record AskRequest(
        String question,
        RetrievalMode retrievalMode,
        Integer retrievalMaxItems,
        Integer retrievalMaxCharacters,
        AnswerContextBudget contextBudget,
        AnswerGenerationOptions generationOptions
) {

    public static final int MAX_RETRIEVAL_ITEMS = 50;
    public static final int MAX_RETRIEVAL_CHARACTERS = 100_000;
    private static final int MAX_QUESTION_CHARACTERS = 4_000;

    public AskRequest {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        question = question.strip();
        if (question.length() > MAX_QUESTION_CHARACTERS) {
            throw new IllegalArgumentException("question must not exceed 4000 characters");
        }
        if (retrievalMode == null) {
            throw new IllegalArgumentException("retrievalMode is required");
        }
        validateBound(retrievalMaxItems, 1, MAX_RETRIEVAL_ITEMS, "retrievalMaxItems");
        validateBound(retrievalMaxCharacters, 1, MAX_RETRIEVAL_CHARACTERS,
                "retrievalMaxCharacters");
        contextBudget = contextBudget == null ? AnswerContextBudget.DEFAULT : contextBudget;
        generationOptions = generationOptions == null
                ? AnswerGenerationOptions.defaults() : generationOptions;
    }

    public AskRequest(String question, RetrievalMode retrievalMode) {
        this(question, retrievalMode, null, null, AnswerContextBudget.DEFAULT,
                AnswerGenerationOptions.defaults());
    }

    public static AskRequest defaults(String question, RetrievalMode retrievalMode) {
        return new AskRequest(question, retrievalMode);
    }

    public RetrievalRequest retrievalRequest() {
        return new RetrievalRequest(question, retrievalMode, retrievalMaxItems,
                retrievalMaxCharacters);
    }

    private static void validateBound(Integer value, int minimum, int maximum, String field) {
        if (value != null && (value < minimum || value > maximum)) {
            throw new IllegalArgumentException(field + " must be between " + minimum
                    + " and " + maximum);
        }
    }
}
