package org.km.llmwiki.ai.answer;

/** Auditable result of deterministic context budgeting. */
public record AnswerContextUsage(int usedEvidenceItems, int usedCodePoints, boolean truncated) {
    public AnswerContextUsage {
        if (usedEvidenceItems < 0 || usedCodePoints < 0) {
            throw new IllegalArgumentException("context usage must not be negative");
        }
    }

    public static AnswerContextUsage empty() {
        return new AnswerContextUsage(0, 0, false);
    }
}
