package org.km.llmwiki.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ModalityRankFusionTest {

    @Test
    void fusedScoreIsSumOfReciprocalRanksPerChannel() {
        Map<CandidateSignal, List<String>> channels = new LinkedHashMap<>();
        channels.put(CandidateSignal.LEXICAL, List.of("WIKI:a", "WIKI:b"));
        channels.put(CandidateSignal.GRAPH, List.of("WIKI:a"));

        List<ModalityRankFusion.FusedIdentity> fused = ModalityRankFusion.fuse(channels);

        assertThat(fused).hasSize(2);
        // "WIKI:a" is hit by two channels: 1/61 + 1/61 beats the single lexical rank 2 hit.
        assertThat(fused.get(0).identity()).isEqualTo("WIKI:a");
        assertThat(fused.get(0).score()).isEqualTo(1.0d / 61 + 1.0d / 61);
        assertThat(fused.get(1).identity()).isEqualTo("WIKI:b");
        assertThat(fused.get(1).score()).isEqualTo(1.0d / 62);
    }

    @Test
    void channelCompletionOrderNeverChangesTheFusedResult() {
        Map<CandidateSignal, List<String>> forward = new LinkedHashMap<>();
        forward.put(CandidateSignal.LEXICAL, List.of("WIKI:a", "SOURCE_CHUNK:7"));
        forward.put(CandidateSignal.VECTOR, List.of("WIKI:b", "WIKI:a"));
        forward.put(CandidateSignal.GRAPH, List.of("SOURCE_CHUNK:7"));

        Map<CandidateSignal, List<String>> backward = new LinkedHashMap<>();
        backward.put(CandidateSignal.GRAPH, List.of("SOURCE_CHUNK:7"));
        backward.put(CandidateSignal.VECTOR, List.of("WIKI:b", "WIKI:a"));
        backward.put(CandidateSignal.LEXICAL, List.of("WIKI:a", "SOURCE_CHUNK:7"));

        assertThat(ModalityRankFusion.fuse(backward))
                .isEqualTo(ModalityRankFusion.fuse(forward));
    }

    @Test
    void backendPermutationInsideAChannelIsTheCallersRankingResponsibility() {
        // Fusion is rank-based: identical rank orders always fuse identically regardless of any
        // raw scores, which are not part of this contract at all.
        Map<CandidateSignal, List<String>> first = Map.of(
                CandidateSignal.LEXICAL, List.of("WIKI:a", "WIKI:b", "WIKI:c"));
        Map<CandidateSignal, List<String>> second = Map.of(
                CandidateSignal.LEXICAL, List.of("WIKI:a", "WIKI:b", "WIKI:c"));

        assertThat(ModalityRankFusion.fuse(second)).isEqualTo(ModalityRankFusion.fuse(first));
    }

    @Test
    void tiesBreakOnApplicationOwnedIdentityOrder() {
        Map<CandidateSignal, List<String>> channels = Map.of(
                CandidateSignal.VECTOR, List.of("WIKI:z", "WIKI:a"));

        List<ModalityRankFusion.FusedIdentity> fused = ModalityRankFusion.fuse(channels);

        assertThat(fused).extracting(ModalityRankFusion.FusedIdentity::identity)
                .containsExactly("WIKI:z", "WIKI:a");
    }

    @Test
    void duplicateIdentityInsideOneChannelKeepsItsFirstRankOnly() {
        Map<CandidateSignal, List<String>> channels = Map.of(
                CandidateSignal.GRAPH, List.of("WIKI:a", "WIKI:a"));

        List<ModalityRankFusion.FusedIdentity> fused = ModalityRankFusion.fuse(channels);

        // A repeated graph identity is a defensive no-op: it must not accumulate extra
        // reciprocal contributions and amplify its own rank.
        assertThat(fused).hasSize(1);
        assertThat(fused.getFirst().score()).isEqualTo(1.0d / 61);
    }

    @Test
    void emptyAndMalformedInputsAreRejectedOrHandled() {
        assertThat(ModalityRankFusion.fuse(Map.of())).isEmpty();
        assertThatThrownBy(() -> ModalityRankFusion.fuse(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModalityRankFusion.fuse(Map.of(
                CandidateSignal.LEXICAL, List.of("WIKI:a", " ")), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void baselinePolicyReproducesTheUniformK60Behavior() {
        Map<CandidateSignal, List<String>> channels = new LinkedHashMap<>();
        channels.put(CandidateSignal.LEXICAL, List.of("WIKI:a", "WIKI:b"));
        channels.put(CandidateSignal.GRAPH, List.of("WIKI:a"));

        assertThat(ModalityRankFusion.fuse(channels, FusionRankingPolicy.baseline()))
                .isEqualTo(ModalityRankFusion.fuse(channels));
    }

    @Test
    void weightedPolicyScalesEachChannelContributionDeterministically() {
        Map<CandidateSignal, List<String>> channels = new LinkedHashMap<>();
        channels.put(CandidateSignal.VECTOR, List.of("WIKI:vector-hit"));
        channels.put(CandidateSignal.GRAPH, List.of("WIKI:graph-hit"));
        FusionRankingPolicy policy = new FusionRankingPolicy("test-weighted", 60, Map.of(
                CandidateSignal.LEXICAL, 1.0d,
                CandidateSignal.VECTOR, 1.0d,
                CandidateSignal.GRAPH, 0.5d));

        List<ModalityRankFusion.FusedIdentity> fused = ModalityRankFusion.fuse(channels, policy);

        // Same rank in both channels: the weighted contribution decides the order.
        assertThat(fused).extracting(ModalityRankFusion.FusedIdentity::identity)
                .containsExactly("WIKI:vector-hit", "WIKI:graph-hit");
        assertThat(fused.get(0).score()).isEqualTo(1.0d / 61);
        assertThat(fused.get(1).score()).isEqualTo(0.5d / 61);
    }

    @Test
    void weightedPolicyStaysPermutationAndCompletionOrderInvariant() {
        Map<CandidateSignal, List<String>> forward = new LinkedHashMap<>();
        forward.put(CandidateSignal.LEXICAL, List.of("WIKI:a", "SOURCE_CHUNK:7"));
        forward.put(CandidateSignal.VECTOR, List.of("WIKI:b", "WIKI:a"));
        forward.put(CandidateSignal.GRAPH, List.of("SOURCE_CHUNK:7"));
        Map<CandidateSignal, List<String>> backward = new LinkedHashMap<>();
        backward.put(CandidateSignal.GRAPH, List.of("SOURCE_CHUNK:7"));
        backward.put(CandidateSignal.VECTOR, List.of("WIKI:b", "WIKI:a"));
        backward.put(CandidateSignal.LEXICAL, List.of("WIKI:a", "SOURCE_CHUNK:7"));
        FusionRankingPolicy policy = new FusionRankingPolicy("test-weighted", 20, Map.of(
                CandidateSignal.LEXICAL, 1.0d,
                CandidateSignal.VECTOR, 0.9d,
                CandidateSignal.GRAPH, 0.8d));

        assertThat(ModalityRankFusion.fuse(backward, policy))
                .isEqualTo(ModalityRankFusion.fuse(forward, policy));
    }

    @Test
    void weightedPolicyNeverAmplifiesDuplicateOrMultiPathHits() {
        Map<CandidateSignal, List<String>> multipath = Map.of(
                CandidateSignal.GRAPH, List.of("WIKI:a", "WIKI:a", "WIKI:a"));
        FusionRankingPolicy policy = new FusionRankingPolicy("test-weighted", 60, Map.of(
                CandidateSignal.LEXICAL, 1.0d,
                CandidateSignal.VECTOR, 1.0d,
                CandidateSignal.GRAPH, 2.0d));

        // Multi-path or duplicate hits stay a single weighted contribution per channel.
        assertThat(ModalityRankFusion.fuse(multipath, policy))
                .extracting(ModalityRankFusion.FusedIdentity::score)
                .containsExactly(2.0d / 61);
    }

    @Test
    void policyBoundsRejectUnboundedParameterizations() {
        assertThatThrownBy(() -> new FusionRankingPolicy("test", 0, Map.of(
                CandidateSignal.LEXICAL, 1.0d, CandidateSignal.VECTOR, 1.0d,
                CandidateSignal.GRAPH, 1.0d))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FusionRankingPolicy("test", 201, Map.of(
                CandidateSignal.LEXICAL, 1.0d, CandidateSignal.VECTOR, 1.0d,
                CandidateSignal.GRAPH, 1.0d))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FusionRankingPolicy("test", 60, Map.of(
                CandidateSignal.LEXICAL, 1.0d, CandidateSignal.VECTOR, 1.0d,
                CandidateSignal.GRAPH, 0.1d))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FusionRankingPolicy("test", 60, Map.of(
                CandidateSignal.LEXICAL, 1.0d, CandidateSignal.VECTOR, 1.0d,
                CandidateSignal.GRAPH, 2.1d))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FusionRankingPolicy("test", 60, Map.of(
                CandidateSignal.LEXICAL, 1.0d, CandidateSignal.VECTOR, 1.0d)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FusionRankingPolicy(" ", 60, Map.of(
                CandidateSignal.LEXICAL, 1.0d, CandidateSignal.VECTOR, 1.0d,
                CandidateSignal.GRAPH, 1.0d))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void versionRegistryResolvesKnownPoliciesAndFailsFastOnUnknown() {
        assertThat(FusionRankingPolicy.byVersion("fusion-rrf-v1"))
                .isEqualTo(FusionRankingPolicy.baseline());
        assertThatThrownBy(() -> FusionRankingPolicy.byVersion("does-not-exist"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
