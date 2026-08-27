package org.km.llmwiki.ai;

import java.util.List;
import java.util.Objects;

/** Validated structured output. It is not a Wiki proposal and has no side effects. */
public record LlmAnalysisResult(LlmProviderMetadata metadata, LlmProposalAction action, String summary,
                                List<AnalysisEvidence> evidence) {

    public LlmAnalysisResult {
        metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        action = Objects.requireNonNull(action, "action must not be null");
        summary = Objects.requireNonNull(summary, "summary must not be null");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("evidence must not be empty");
        }
    }
}
