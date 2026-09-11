package org.km.llmwiki.ai.ask;

import org.km.llmwiki.ai.answer.AnswerContextDiagnostics;
import org.km.llmwiki.rag.RerankNoOpReason;
import org.km.llmwiki.rag.RerankStatus;

import java.util.Objects;

/** Bounded, non-content execution metadata exposed for observability and Ask API mapping. */
public record AskExecutionMetadata(
        int retrievedEvidenceItems,
        int contextEvidenceItems,
        int contextCodePoints,
        boolean contextTruncated,
        AnswerContextDiagnostics contextDiagnostics,
        String rerankPolicyVersion,
        RerankStatus rerankStatus,
        RerankNoOpReason rerankNoOpReason
) {

    /** Source-compatible constructor for callers predating context observability. */
    public AskExecutionMetadata(int retrievedEvidenceItems, int contextEvidenceItems,
                                int contextCodePoints, boolean contextTruncated) {
        this(retrievedEvidenceItems, contextEvidenceItems, contextCodePoints, contextTruncated,
                AnswerContextDiagnostics.legacy(retrievedEvidenceItems, contextEvidenceItems,
                        contextCodePoints, contextTruncated), null, null, null);
    }

    /** Source-compatible constructor for callers predating rerank observability. */
    public AskExecutionMetadata(int retrievedEvidenceItems, int contextEvidenceItems,
                                int contextCodePoints, boolean contextTruncated,
                                AnswerContextDiagnostics contextDiagnostics) {
        this(retrievedEvidenceItems, contextEvidenceItems, contextCodePoints, contextTruncated,
                contextDiagnostics, null, null, null);
    }

    /** Immutable copy carrying a provider outcome while preserving rerank metadata. */
    public AskExecutionMetadata withProviderOutcome(
            org.km.llmwiki.ai.answer.ProviderUsageStatus status,
            org.km.llmwiki.ai.answer.AnswerUsageMetadata usage, Long answerLatencyMs) {
        return new AskExecutionMetadata(retrievedEvidenceItems, contextEvidenceItems,
                contextCodePoints, contextTruncated,
                contextDiagnostics.withProviderUsage(status, usage, answerLatencyMs),
                rerankPolicyVersion, rerankStatus, rerankNoOpReason);
    }

    /** Immutable copy carrying the rerank execution outcome for this Ask. */
    public AskExecutionMetadata withRerankOutcome(String policyVersion, RerankStatus status,
                                                  RerankNoOpReason noOpReason) {
        return new AskExecutionMetadata(retrievedEvidenceItems, contextEvidenceItems,
                contextCodePoints, contextTruncated, contextDiagnostics, policyVersion, status,
                noOpReason);
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
        // Rerank metadata is execution-level truth: an APPLIED rerank has no no-op reason, a
        // no-op decision always carries one, and the policy version must be a safe identifier.
        if (rerankStatus != null) {
            if (rerankStatus == RerankStatus.APPLIED && rerankNoOpReason != null) {
                throw new IllegalArgumentException(
                        "an applied rerank must not carry a no-op reason");
            }
            if (rerankStatus != RerankStatus.APPLIED && rerankNoOpReason == null) {
                throw new IllegalArgumentException(
                        "a no-op rerank must carry a typed no-op reason");
            }
            if (rerankPolicyVersion != null
                    && !SAFE_RERANK_POLICY_VERSION.matcher(rerankPolicyVersion).matches()) {
                throw new IllegalArgumentException("rerank policy version is not a safe identifier");
            }
        } else if (rerankPolicyVersion != null || rerankNoOpReason != null) {
            throw new IllegalArgumentException(
                    "rerank metadata requires a typed rerank status");
        }
    }

    private static final java.util.regex.Pattern SAFE_RERANK_POLICY_VERSION =
            java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._-]*");
}
