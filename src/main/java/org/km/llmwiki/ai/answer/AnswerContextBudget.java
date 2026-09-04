package org.km.llmwiki.ai.answer;

/** Application-owned deterministic context budget; it is deliberately not provider token counting. */
public record AnswerContextBudget(int maxEvidenceItems, int maxCodePointsPerItem,
                                  int maxTotalCodePoints) {
    public static final AnswerContextBudget DEFAULT = new AnswerContextBudget(32, 4_000, 16_000);

    public AnswerContextBudget {
        if (maxEvidenceItems < 1 || maxEvidenceItems > AnswerContext.MAX_REFERENCES) {
            throw new IllegalArgumentException("maxEvidenceItems must be between 1 and "
                    + AnswerContext.MAX_REFERENCES);
        }
        if (maxCodePointsPerItem < 1 || maxTotalCodePoints < 1) {
            throw new IllegalArgumentException("context code-point budgets must be positive");
        }
        if (maxCodePointsPerItem > maxTotalCodePoints) {
            throw new IllegalArgumentException("per-item budget must not exceed total budget");
        }
    }
}
