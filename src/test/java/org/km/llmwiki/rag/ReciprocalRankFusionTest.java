package org.km.llmwiki.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.search.SearchCandidate;
import org.km.llmwiki.search.SearchResultKind;
import org.km.llmwiki.search.SearchWorkspaceProvenance;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ReciprocalRankFusionTest {

    private final ReciprocalRankFusion ranker = new ReciprocalRankFusion();

    @Test
    void fusesLexicalOnlyVectorOnlyAndSharedCandidatesWithRrfK60() {
        SearchCandidate exact = candidate(SearchResultKind.WIKI, "exact", 100.0);
        SearchCandidate semantic = candidate(SearchResultKind.WIKI, "semantic", 0.01);
        SearchCandidate sharedLexical = candidate(SearchResultKind.SOURCE_CHUNK, "shared", 2.0);
        SearchCandidate sharedVector = candidate(SearchResultKind.SOURCE_CHUNK, "shared", 0.2);

        List<SearchCandidate> fused = ranker.fuse(
                List.of(exact, sharedLexical), List.of(sharedVector, semantic), 10);

        assertThat(fused).extracting(SearchCandidate::stableId)
                .containsExactly("shared", "exact", "semantic");
        assertThat(fused.getFirst().score()).isEqualTo(1.0d / 62.0d + 1.0d / 61.0d);
        assertThat(fused.get(1).score()).isEqualTo(1.0d / 61.0d);
        assertThat(fused.get(2).score()).isEqualTo(1.0d / 62.0d);
    }

    @Test
    void deDuplicatesRepeatedIdentityWithinOneSignalAndUsesStableTieBreak() {
        SearchCandidate wikiB = candidate(SearchResultKind.WIKI, "b", 1.0);
        SearchCandidate wikiA = candidate(SearchResultKind.WIKI, "a", 1.0);
        SearchCandidate duplicateA = candidate(SearchResultKind.WIKI, "a", 999.0);
        SearchCandidate sourceA = candidate(SearchResultKind.SOURCE_CHUNK, "a", 1.0);

        List<SearchCandidate> fused = ranker.fuse(
                List.of(wikiB, wikiA, duplicateA), List.of(sourceA), 10);

        assertThat(fused).extracting(candidate -> candidate.kind().name() + ":" + candidate.stableId())
                .containsExactly("SOURCE_CHUNK:a", "WIKI:a", "WIKI:b");
        assertThat(fused.get(1).score()).isEqualTo(1.0d / 61.0d);
    }

    @Test
    void rankBasedFusionIsInvariantToRawScoreScale() {
        List<SearchCandidate> lexical = List.of(
                candidate(SearchResultKind.WIKI, "first", 0.0001),
                candidate(SearchResultKind.WIKI, "second", 0.00001));
        List<SearchCandidate> vector = List.of(
                candidate(SearchResultKind.WIKI, "second", 9000.0),
                candidate(SearchResultKind.WIKI, "first", 10.0));

        assertThat(ranker.fuse(lexical, vector, 10).stream()
                .map(SearchCandidate::stableId).toList())
                .containsExactlyElementsOf(ranker.fuse(
                        List.of(candidate(SearchResultKind.WIKI, "first", 10_000),
                                candidate(SearchResultKind.WIKI, "second", 100)),
                        List.of(candidate(SearchResultKind.WIKI, "second", 0.9),
                                candidate(SearchResultKind.WIKI, "first", 0.1)), 10).stream()
                        .map(SearchCandidate::stableId).toList());
    }

    @Test
    void rejectsMalformedCandidatesAndInvalidLimits() {
        assertThatThrownBy(() -> ranker.fuse(List.of(), List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ranker.fuse(java.util.Arrays.asList((SearchCandidate) null), List.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SearchCandidate candidate(SearchResultKind kind, String id, double score) {
        return new SearchCandidate(kind, id, score, "snippet",
                new SearchWorkspaceProvenance(7L, "test"),
                kind == SearchResultKind.WIKI ? id : null,
                kind == SearchResultKind.WIKI ? id : null,
                kind == SearchResultKind.WIKI ? "CONCEPT" : null,
                kind == SearchResultKind.WIKI ? "vault/" + id + ".md" : null,
                kind == SearchResultKind.WIKI ? 1 : null,
                "hash-" + id, null, null,
                kind == SearchResultKind.SOURCE_CHUNK ? Long.valueOf(id.hashCode()) : null,
                kind == SearchResultKind.SOURCE_CHUNK ? 1L : null,
                kind == SearchResultKind.SOURCE_CHUNK ? "source.txt" : null,
                kind == SearchResultKind.SOURCE_CHUNK ? 1 : null,
                kind == SearchResultKind.SOURCE_CHUNK ? 1 : null, null, null);
    }
}
