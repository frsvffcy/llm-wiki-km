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
}
