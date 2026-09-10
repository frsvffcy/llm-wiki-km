package org.km.llmwiki.ai.answer;

import org.km.llmwiki.rag.EvidenceBundle;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, request-scoped observability for one Ask context execution.
 *
 * <p>This type deliberately contains only application-owned aggregates. It never carries
 * evidence content, prompts, provider payloads, paths, backend identifiers, or exception text.
 * Code-point measurements describe the application context; token measurements are optional
 * counters reported by the answer provider and must not be used as a context-budget authority.
 */
public record AnswerContextDiagnostics(
        int retrievedEvidenceCount,
        int admittedEvidenceCount,
        int answerContextBlockCount,
        int originalCodePoints,
        int baselineCodePoints,
        int projectedCodePoints,
        double reductionRatio,
        boolean truncated,
        boolean compacted,
        String contextPolicyVersion,
        Map<ProjectionKind, Integer> projectionKindDistribution,
        boolean projectionFallbackUsed,
        ContextProjectionFailureType projectionFailureType,
        Long projectionLatencyMs,
        Long answerLatencyMs,
        ProviderUsageStatus providerUsageStatus,
        Integer providerInputTokens,
        Integer providerOutputTokens,
        Integer providerTotalTokens
) {

    public AnswerContextDiagnostics {
        if (retrievedEvidenceCount < 0 || admittedEvidenceCount < 0
                || answerContextBlockCount < 0 || originalCodePoints < 0
                || baselineCodePoints < 0 || projectedCodePoints < 0) {
            throw new IllegalArgumentException("context diagnostics counts must not be negative");
        }
        if (admittedEvidenceCount > retrievedEvidenceCount
                || answerContextBlockCount > admittedEvidenceCount) {
            throw new IllegalArgumentException(
                    "context diagnostics counts must preserve retrieval lifecycle ordering");
        }
        if (baselineCodePoints > originalCodePoints || projectedCodePoints > baselineCodePoints) {
            throw new IllegalArgumentException(
                    "context diagnostics code points must not grow across projection stages");
        }
        if (!Double.isFinite(reductionRatio) || reductionRatio < 0.0d
                || reductionRatio > 1.0d) {
            throw new IllegalArgumentException("context reduction ratio must be between 0 and 1");
        }
        boolean actualCompaction = projectedCodePoints < baselineCodePoints;
        if (compacted != actualCompaction) {
            throw new IllegalArgumentException(
                    "context compacted flag must match projected and baseline code points");
        }
        if (contextPolicyVersion != null && contextPolicyVersion.isBlank()) {
            throw new IllegalArgumentException("context policy version must not be blank");
        }
        if (projectionFallbackUsed != (projectionFailureType != null)) {
            throw new IllegalArgumentException(
                    "projection fallback and failure type must agree");
        }
        projectionKindDistribution = normalizedProjectionKinds(projectionKindDistribution,
                answerContextBlockCount);
        requireNonNegative(projectionLatencyMs, "projectionLatencyMs");
        requireNonNegative(answerLatencyMs, "answerLatencyMs");
        providerUsageStatus = Objects.requireNonNull(providerUsageStatus,
                "provider usage status must not be null");
        requireNonNegative(providerInputTokens, "providerInputTokens");
        requireNonNegative(providerOutputTokens, "providerOutputTokens");
        requireNonNegative(providerTotalTokens, "providerTotalTokens");
        boolean hasUsage = providerInputTokens != null || providerOutputTokens != null
                || providerTotalTokens != null;
        if (providerUsageStatus == ProviderUsageStatus.AVAILABLE && !hasUsage) {
            throw new IllegalArgumentException(
                    "available provider usage requires at least one counter");
        }
        if (providerUsageStatus != ProviderUsageStatus.AVAILABLE && hasUsage) {
            throw new IllegalArgumentException(
                    "unavailable provider usage must not contain counters");
        }
    }

    /**
     * Builds diagnostics directly from the authoritative retrieval and projection results.
     * Projection reduction is application-owned and uses original evidence size as its
     * denominator, while the projection result retains its #309 baseline-denominator ratio.
     */
    public static AnswerContextDiagnostics from(EvidenceBundle evidence,
                                                ContextProjectionResult projection,
                                                Long projectionLatencyMs,
                                                Long answerLatencyMs,
                                                ProviderUsageStatus providerUsageStatus,
                                                AnswerUsageMetadata usage) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(projection, "projection must not be null");
        Objects.requireNonNull(providerUsageStatus, "provider usage status must not be null");
        return new AnswerContextDiagnostics(
                evidence.searchedCandidateCount(),
                evidence.items().size(),
                projection.context().blocks().size(),
                projection.originalCodePoints(),
                projection.baselineCodePoints(),
                projection.projectedCodePoints(),
                reductionRatio(projection.originalCodePoints(), projection.projectedCodePoints()),
                projection.baselineTruncated(),
                projection.projectedCodePoints() < projection.baselineCodePoints(),
                projection.policyVersion(),
                projectionKindDistribution(projection.blocks()),
                projection.fallbackUsed(),
                projection.failureType(),
                projectionLatencyMs,
                answerLatencyMs,
                providerUsageStatus,
                usage == null ? null : usage.inputTokens(),
                usage == null ? null : usage.outputTokens(),
                usage == null ? null : usage.totalTokens());
    }

    /** Empty diagnostic state used when retrieval never produced a projection. */
    public static AnswerContextDiagnostics empty() {
        return new AnswerContextDiagnostics(0, 0, 0, 0, 0, 0, 0.0d,
                false, false, null, zeroProjectionKinds(), false, null,
                null, null, ProviderUsageStatus.NOT_ATTEMPTED, null, null, null);
    }

    /** Compatibility projection for callers that still construct the four-field metadata. */
    public static AnswerContextDiagnostics legacy(int retrievedEvidenceItems,
                                                  int contextEvidenceItems,
                                                  int contextCodePoints,
                                                  boolean contextTruncated) {
        ProjectionKind kind = contextTruncated ? ProjectionKind.TRUNCATED : ProjectionKind.VERBATIM;
        Map<ProjectionKind, Integer> kinds = zeroProjectionKinds();
        kinds.put(kind, contextEvidenceItems);
        return new AnswerContextDiagnostics(retrievedEvidenceItems, contextEvidenceItems,
                contextEvidenceItems, contextCodePoints, contextCodePoints, contextCodePoints,
                0.0d, contextTruncated, false, null, kinds, false, null, null, null,
                ProviderUsageStatus.NOT_ATTEMPTED, null, null, null);
    }

    /** Adds the provider outcome while retaining the context projection aggregates. */
    public AnswerContextDiagnostics withProviderUsage(ProviderUsageStatus status,
                                                      AnswerUsageMetadata usage,
                                                      Long answerLatencyMs) {
        return new AnswerContextDiagnostics(retrievedEvidenceCount, admittedEvidenceCount,
                answerContextBlockCount, originalCodePoints, baselineCodePoints,
                projectedCodePoints, reductionRatio, truncated, compacted, contextPolicyVersion,
                projectionKindDistribution, projectionFallbackUsed, projectionFailureType,
                projectionLatencyMs, answerLatencyMs, status,
                usage == null ? null : usage.inputTokens(),
                usage == null ? null : usage.outputTokens(),
                usage == null ? null : usage.totalTokens());
    }

    /** The packed baseline size before the active projection policy runs. */
    public int packedCodePoints() {
        return baselineCodePoints;
    }

    private static Map<ProjectionKind, Integer> projectionKindDistribution(
            List<ProjectedEvidenceBlock> blocks) {
        Map<ProjectionKind, Integer> counts = zeroProjectionKinds();
        for (ProjectedEvidenceBlock block : blocks) {
            counts.compute(block.projectionKind(), (ignored, count) -> count + 1);
        }
        return counts;
    }

    private static double reductionRatio(int originalCodePoints, int projectedCodePoints) {
        return originalCodePoints == 0 ? 0.0d
                : 1.0d - (double) projectedCodePoints / originalCodePoints;
    }

    private static Map<ProjectionKind, Integer> normalizedProjectionKinds(
            Map<ProjectionKind, Integer> values, int blockCount) {
        Map<ProjectionKind, Integer> counts = zeroProjectionKinds();
        if (values != null) {
            values.forEach((kind, count) -> {
                if (kind == null || count == null || count < 0) {
                    throw new IllegalArgumentException(
                            "projection kind distribution must contain non-negative counts");
                }
                counts.put(kind, count);
            });
        }
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total != blockCount) {
            throw new IllegalArgumentException(
                    "projection kind distribution must cover every context block");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    private static Map<ProjectionKind, Integer> zeroProjectionKinds() {
        Map<ProjectionKind, Integer> counts = new EnumMap<>(ProjectionKind.class);
        for (ProjectionKind kind : ProjectionKind.values()) {
            counts.put(kind, 0);
        }
        return counts;
    }

    private static void requireNonNegative(Integer value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }

    private static void requireNonNegative(Long value, String field) {
        if (value != null && value < 0L) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }

    /*
     * Latency is a runtime measurement, not semantic Ask output. Excluding it from equality
     * keeps deterministic result comparisons meaningful without hiding it from the API.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AnswerContextDiagnostics that)) return false;
        return retrievedEvidenceCount == that.retrievedEvidenceCount
                && admittedEvidenceCount == that.admittedEvidenceCount
                && answerContextBlockCount == that.answerContextBlockCount
                && originalCodePoints == that.originalCodePoints
                && baselineCodePoints == that.baselineCodePoints
                && projectedCodePoints == that.projectedCodePoints
                && Double.compare(reductionRatio, that.reductionRatio) == 0
                && truncated == that.truncated
                && compacted == that.compacted
                && projectionFallbackUsed == that.projectionFallbackUsed
                && Objects.equals(contextPolicyVersion, that.contextPolicyVersion)
                && Objects.equals(projectionKindDistribution, that.projectionKindDistribution)
                && projectionFailureType == that.projectionFailureType
                && providerUsageStatus == that.providerUsageStatus
                && Objects.equals(providerInputTokens, that.providerInputTokens)
                && Objects.equals(providerOutputTokens, that.providerOutputTokens)
                && Objects.equals(providerTotalTokens, that.providerTotalTokens);
    }

    @Override
    public int hashCode() {
        return Objects.hash(retrievedEvidenceCount, admittedEvidenceCount,
                answerContextBlockCount, originalCodePoints, baselineCodePoints,
                projectedCodePoints, reductionRatio, truncated, compacted, contextPolicyVersion,
                projectionKindDistribution, projectionFallbackUsed, projectionFailureType,
                providerUsageStatus, providerInputTokens, providerOutputTokens,
                providerTotalTokens);
    }
}
