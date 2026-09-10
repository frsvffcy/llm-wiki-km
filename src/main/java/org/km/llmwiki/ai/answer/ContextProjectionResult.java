package org.km.llmwiki.ai.answer;

import java.util.List;

/**
 * Ephemeral result of one context projection: the projected {@link AnswerContext} the answer
 * provider will see, plus safe typed projection metadata. The complete {@link
 * org.km.llmwiki.rag.EvidenceBundle} remains the grounded/citation authority; a projection is
 * one Ask execution's provider representation and is never persisted as knowledge.
 */
public record ContextProjectionResult(
        String policyVersion,
        AnswerContext context,
        int originalEvidenceCount,
        int projectedEvidenceCount,
        int originalCodePoints,
        int baselineCodePoints,
        int projectedCodePoints,
        double reductionRatio,
        List<ProjectedEvidenceBlock> blocks,
        boolean fallbackUsed,
        ContextProjectionFailureType failureType
) {
    public ContextProjectionResult {
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("policy version is required");
        }
        if (context == null) {
            throw new IllegalArgumentException("projected context is required");
        }
        if (originalEvidenceCount < 0 || projectedEvidenceCount < 0
                || originalCodePoints < 0 || baselineCodePoints < 0 || projectedCodePoints < 0) {
            throw new IllegalArgumentException("projection counts must not be negative");
        }
        if (projectedCodePoints > baselineCodePoints) {
            throw new IllegalArgumentException(
                    "projection must not exceed the bounded baseline code points");
        }
        if (reductionRatio < 0.0d || reductionRatio > 1.0d) {
            throw new IllegalArgumentException("reduction ratio must be between 0 and 1");
        }
        if (blocks == null || blocks.size() != context.blocks().size()) {
            throw new IllegalArgumentException("projection blocks must align with context blocks");
        }
        if (fallbackUsed && failureType == null) {
            throw new IllegalArgumentException("a fallback projection requires a typed failure");
        }
        if (!fallbackUsed && failureType != null) {
            throw new IllegalArgumentException("a non-fallback projection must not carry a failure");
        }
        blocks = List.copyOf(blocks);
    }

    /** Safe typed view of the policy that produced this projection. */
    public String policyVersion() {
        return policyVersion;
    }
}
