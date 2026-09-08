package org.km.llmwiki.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Identity-level reciprocal rank fusion across modality channels. Scores are computed from
 * per-channel one-based ranks only; raw backend scores, vendor similarities, graph path counts,
 * and backend iteration order never influence the fused result. Channels may be fused in any
 * completion order — the contribution of one identity is the same sum either way.
 */
final class ModalityRankFusion {

    static final int DEFAULT_K = 60;

    private ModalityRankFusion() {
    }

    /**
     * Fuses per-channel identity orders (rank 1 = first entry). Duplicate identities inside one
     * channel keep their first rank; the same identity hit by multiple channels accumulates one
     * reciprocal contribution per channel. Final order: fused score descending, then identity
     * ascending as the application-owned tie-break.
     */
    static List<FusedIdentity> fuse(Map<CandidateSignal, List<String>> channels) {
        if (channels == null) {
            throw new IllegalArgumentException("Fusion channels are required");
        }
        if (channels.isEmpty()) {
            return List.of();
        }
        return fuse(channels, DEFAULT_K);
    }

    static List<FusedIdentity> fuse(Map<CandidateSignal, List<String>> channels, int k) {
        if (channels == null || k < 1) {
            throw new IllegalArgumentException("Fusion channels and positive k are required");
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<CandidateSignal, List<String>> orderedChannels = new EnumMap<>(CandidateSignal.class);
        orderedChannels.putAll(channels);
        for (Map.Entry<CandidateSignal, List<String>> channel : orderedChannels.entrySet()) {
            if (channel.getValue() == null) {
                continue;
            }
            // A repeated identity inside one channel keeps its first rank only: multi-path or
            // duplicate hits must never accumulate extra reciprocal contributions.
            Set<String> channelSeen = new HashSet<>();
            int rank = 0;
            for (String identity : channel.getValue()) {
                if (identity == null || identity.isBlank()) {
                    throw new IllegalArgumentException("Fusion identities must not be blank");
                }
                if (!channelSeen.add(identity)) {
                    continue;
                }
                rank++;
                scores.putIfAbsent(identity, 0.0d);
                scores.put(identity, scores.get(identity) + 1.0d / (k + rank));
            }
        }
        List<FusedIdentity> fused = new ArrayList<>(scores.size());
        scores.forEach((identity, score) -> fused.add(new FusedIdentity(identity, score)));
        fused.sort(Comparator.comparingDouble(FusedIdentity::score).reversed()
                .thenComparing(FusedIdentity::identity));
        return List.copyOf(fused);
    }

    record FusedIdentity(String identity, double score) {
    }
}
