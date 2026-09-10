package org.km.llmwiki.ai.ask;

import org.km.llmwiki.ai.answer.AnswerContextDiagnostics;

import java.util.Objects;

/** Bounded, non-content execution metadata exposed for observability and Ask API mapping. */
public record AskExecutionMetadata(
        int retrievedEvidenceItems,
        int contextEvidenceItems,
        int contextCodePoints,
        boolean contextTruncated,
        AnswerContextDiagnostics contextDiagnostics
) {

    /** Source-compatible constructor for callers predating context observability. */
    public AskExecutionMetadata(int retrievedEvidenceItems, int contextEvidenceItems,
                                int contextCodePoints, boolean contextTruncated) {
        this(retrievedEvidenceItems, contextEvidenceItems, contextCodePoints, contextTruncated,
                AnswerContextDiagnostics.legacy(retrievedEvidenceItems, contextEvidenceItems,
                        contextCodePoints, contextTruncated));
    }

    /** Builds the legacy fields and diagnostics from one authoritative projection. */
    public static AskExecutionMetadata fromDiagnostics(AnswerContextDiagnostics diagnostics) {
        Objects.requireNonNull(diagnostics, "context diagnostics must not be null");
        return new AskExecutionMetadata(diagnostics.retrievedEvidenceCount(),
                diagnostics.answerContextBlockCount(), diagnostics.projectedCodePoints(),
                diagnostics.truncated(), diagnostics);
    }

    public AskExecutionMetadata {
        if (retrievedEvidenceItems < 0 || contextEvidenceItems < 0 || contextCodePoints < 0) {
            throw new IllegalArgumentException("Ask execution counts must not be negative");
        }
        contextDiagnostics = Objects.requireNonNull(contextDiagnostics,
                "context diagnostics must not be null");
        if (retrievedEvidenceItems != contextDiagnostics.retrievedEvidenceCount()
                || contextEvidenceItems != contextDiagnostics.answerContextBlockCount()
                || contextCodePoints != contextDiagnostics.projectedCodePoints()
                || contextTruncated != contextDiagnostics.truncated()) {
            throw new IllegalArgumentException(
                    "legacy execution fields must agree with context diagnostics");
        }
    }
}
