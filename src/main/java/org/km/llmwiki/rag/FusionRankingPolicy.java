package org.km.llmwiki.rag;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned, bounded, application-owned fusion ranking policy for the three-channel fused
 * boundary. A policy is a deterministic parameterization of identity-level reciprocal rank
 * fusion: channel contributions stay rank-based (one-based rank, in-channel first-rank dedupe)
 * and the modality weights are fixed application constants — never raw backend scores, vendor
 * similarities, path counts, or backend order. The final order is always fused score
 * descending with the identity-ascending application tie-break, so identical inputs produce an
 * identical canonical order regardless of modality completion order or backend iteration.
 *
 * <p>Bounds exist so a calibration experiment can never smuggle in an unbounded amplifier:
 * {@code k} is a positive damping constant and every modality weight is a fixed multiplier
 * inside a narrow range. Candidate policies are compared offline over the versioned golden
 * corpus plus holdout scenarios before a version is promoted into this registry.
 */
public record FusionRankingPolicy(String version, int k,
                                  Map<CandidateSignal, Double> modalityWeights) {

    public static final int MIN_K = 1;
    public static final int MAX_K = 200;
    public static final double MIN_WEIGHT = 0.25d;
    public static final double MAX_WEIGHT = 2.0d;

    /**
     * STORY-812 baseline: uniform reciprocal rank fusion with k=60. Kept as a registry entry so
     * the calibrated policy always remains comparable to the recorded baseline.
     */
    private static final FusionRankingPolicy BASELINE = new FusionRankingPolicy(
            "fusion-rrf-v1", 60, Map.of(
            CandidateSignal.LEXICAL, 1.0d,
            CandidateSignal.VECTOR, 1.0d,
            CandidateSignal.GRAPH, 1.0d));

    public FusionRankingPolicy {
        Objects.requireNonNull(version, "Policy version is required");
        if (version.isBlank()) {
            throw new IllegalArgumentException("Policy version must not be blank");
        }
        if (k < MIN_K || k > MAX_K) {
            throw new IllegalArgumentException(
                    "Fusion k must be within [" + MIN_K + ", " + MAX_K + "]: " + k);
        }
        Objects.requireNonNull(modalityWeights, "Modality weights are required");
        if (modalityWeights.size() != CandidateSignal.values().length
                || !modalityWeights.containsKey(CandidateSignal.LEXICAL)
                || !modalityWeights.containsKey(CandidateSignal.VECTOR)
                || !modalityWeights.containsKey(CandidateSignal.GRAPH)) {
            throw new IllegalArgumentException(
                    "Fusion policy must define a weight for every modality");
        }
        for (Map.Entry<CandidateSignal, Double> weight : modalityWeights.entrySet()) {
            Double value = weight.getValue();
            if (value == null || !Double.isFinite(value)
                    || value < MIN_WEIGHT || value > MAX_WEIGHT) {
                throw new IllegalArgumentException("Fusion weight for " + weight.getKey()
                        + " must be within [" + MIN_WEIGHT + ", " + MAX_WEIGHT + "]: " + value);
            }
        }
        modalityWeights = Map.copyOf(new EnumMap<>(modalityWeights));
        version = version.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * STORY-813 selected policy: baseline uniform k=60 RRF with the graph channel contribution
     * damped to a bounded 0.75. Promoted on calibration evidence (golden + holdout corpora,
     * leave-one-query-out, and a stable sensitivity region); see docs/development/testing.md.
     */
    private static final FusionRankingPolicy GRAPH_DAMPED = new FusionRankingPolicy(
            "fusion-rrf-v2-graph-damped", 60, Map.of(
            CandidateSignal.LEXICAL, 1.0d,
            CandidateSignal.VECTOR, 1.0d,
            CandidateSignal.GRAPH, 0.75d));

    /** Current production policy selected by the STORY-813 offline calibration evidence. */
    private static final FusionRankingPolicy PRODUCTION = GRAPH_DAMPED;

    /** The STORY-812 baseline policy, for offline comparison and diagnostics. */
    public static FusionRankingPolicy baseline() {
        return BASELINE;
    }

    /** The policy production currently serves; also the default for tests and fixtures. */
    public static FusionRankingPolicy production() {
        return PRODUCTION;
    }

    /** Resolves a known registry policy by version; unknown versions fail fast. */
    public static FusionRankingPolicy byVersion(String version) {
        String normalized = version == null ? "" : version.trim().toLowerCase(Locale.ROOT);
        if (BASELINE.version().equals(normalized)) {
            return BASELINE;
        }
        if (GRAPH_DAMPED.version().equals(normalized)) {
            return GRAPH_DAMPED;
        }
        throw new IllegalArgumentException("Unknown fusion ranking policy version: " + version);
    }

    public double weight(CandidateSignal signal) {
        return modalityWeights.get(signal);
    }
}
