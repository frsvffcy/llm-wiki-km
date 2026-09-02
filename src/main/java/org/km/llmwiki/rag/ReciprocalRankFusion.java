package org.km.llmwiki.rag;

import org.km.llmwiki.search.SearchCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deterministic, scale-independent Reciprocal Rank Fusion.  RRF uses k=60 and one-based ranks;
 * raw lexical scores and vector similarities are never compared or added.
 */
@Component
public final class ReciprocalRankFusion implements FusionRanker {

    public static final int DEFAULT_K = 60;

    private static final Comparator<SearchCandidate> SIGNAL_ORDER =
            Comparator.comparingDouble(SearchCandidate::score).reversed()
                    .thenComparing(candidate -> candidate.kind().name())
                    .thenComparing(SearchCandidate::stableId);

    private final int k;

    public ReciprocalRankFusion() {
        this(DEFAULT_K);
    }

    public ReciprocalRankFusion(int k) {
        if (k < 1) {
            throw new IllegalArgumentException("RRF k must be positive");
        }
        this.k = k;
    }

    public int k() {
        return k;
    }

    @Override
    public List<SearchCandidate> fuse(List<SearchCandidate> lexical,
                                      List<SearchCandidate> vector,
                                      int limit) {
        if (lexical == null || vector == null || limit < 1) {
            throw new IllegalArgumentException("candidate lists and positive limit are required");
        }
        Map<String, Fused> merged = new LinkedHashMap<>();
        addSignal(merged, lexical, CandidateSignal.LEXICAL);
        addSignal(merged, vector, CandidateSignal.VECTOR);
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(Fused::score).reversed()
                        .thenComparing(f -> f.candidate().kind().name())
                        .thenComparing(f -> f.candidate().stableId()))
                .limit(limit)
                .map(f -> f.candidate().withScore(f.score()))
                .toList();
    }

    /** Exposes rank inputs for tests and quality measurement without provider-native types. */
    public List<RankingInput> rankInputs(List<SearchCandidate> candidates, CandidateSignal signal) {
        if (candidates == null || signal == null) {
            throw new IllegalArgumentException("candidates and signal are required");
        }
        Map<String, SearchCandidate> unique = new LinkedHashMap<>();
        for (SearchCandidate candidate : candidates) {
            if (candidate == null || candidate.kind() == null || candidate.stableId() == null
                    || candidate.stableId().isBlank() || !Double.isFinite(candidate.score())) {
                throw new IllegalArgumentException("candidate identity and finite score are required");
            }
            String identity = candidate.kind().name() + ":" + candidate.stableId();
            SearchCandidate previous = unique.get(identity);
            // A provider may defensively repeat an identity. Keep the best ranked snapshot and
            // count it only once; cross-signal duplicates are merged by addSignal below.
            if (previous == null || SIGNAL_ORDER.compare(candidate, previous) < 0) {
                unique.put(identity, candidate);
            }
        }
        List<SearchCandidate> ordered = new ArrayList<>(unique.values());
        ordered.sort(SIGNAL_ORDER);
        List<RankingInput> result = new ArrayList<>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            result.add(new RankingInput(signal, ordered.get(index), index + 1));
        }
        return List.copyOf(result);
    }

    private void addSignal(Map<String, Fused> merged, List<SearchCandidate> candidates,
                           CandidateSignal signal) {
        for (RankingInput input : rankInputs(candidates, signal)) {
            String identity = input.stableIdentity();
            double contribution = 1.0d / (k + input.rank());
            Fused existing = merged.get(identity);
            if (existing == null) {
                merged.put(identity, new Fused(input.candidate(), contribution));
            } else {
                // Keep the first signal's authority snapshot. Conflicting snapshots remain
                // conservative: authority revalidation will reject stale hashes/revisions.
                merged.put(identity, new Fused(existing.candidate(), existing.score() + contribution));
            }
        }
    }

    private record Fused(SearchCandidate candidate, double score) {
    }
}
