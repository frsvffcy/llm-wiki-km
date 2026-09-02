package org.km.llmwiki.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.search.SearchCandidate;
import org.km.llmwiki.search.SearchResultKind;
import org.km.llmwiki.search.SearchWorkspaceProvenance;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Offline, deterministic fixture for comparing FTS-only and lexical/vector retrieval quality. */
@Tag("contract")
class HybridRetrievalQualityMeasurementTest {

    private final ReciprocalRankFusion ranker = new ReciprocalRankFusion();

    @Test
    void reportsReproducibleCjkEnglishSynonymExactAndMixedCorpusMetrics() {
        List<Fixture> fixtures = List.of(
                Fixture.of("CJK 短詞與技術詞", List.of("cjk-exact", "cjk-noise"),
                        List.of("cjk-semantic", "cjk-exact"), Set.of("cjk-exact", "cjk-semantic")),
                Fixture.of("English technical token", List.of("java-token"),
                        List.of("java-token", "english-semantic"), Set.of("java-token", "english-semantic")),
                Fixture.of("同義詞改寫", List.of("synonym-noise"),
                        List.of("synonym-relevant"), Set.of("synonym-relevant")),
                Fixture.of("lexical exact hit", List.of("exact-hit"), List.of(), Set.of("exact-hit")),
                Fixture.of("semantic-only relevant hit", List.of(), List.of("semantic-only"),
                        Set.of("semantic-only")),
                Fixture.of("mixed Wiki + Source", List.of("wiki-exact", "source-noise"),
                        List.of("source-semantic", "wiki-exact"), Set.of("wiki-exact", "source-semantic")));

        Metrics lexical = aggregate(fixtures, fixture -> fixture.lexical());
        Metrics hybrid = aggregate(fixtures, fixture -> ranker.fuse(
                fixture.lexical(), fixture.vector(), 2));

        // These values are the checked-in offline baseline; no provider, network, or randomness.
        assertThat(lexical.recallAt2()).isCloseTo(0.4166666667d,
                org.assertj.core.data.Offset.offset(1.0e-9));
        assertThat(lexical.precisionAt2()).isCloseTo(0.3333333333d,
                org.assertj.core.data.Offset.offset(1.0e-9));
        assertThat(lexical.mrr()).isCloseTo(0.6666666667d,
                org.assertj.core.data.Offset.offset(1.0e-9));
        assertThat(hybrid.recallAt2()).isCloseTo(1.0d,
                org.assertj.core.data.Offset.offset(1.0e-9));
        assertThat(hybrid.precisionAt2()).isCloseTo(0.75d,
                org.assertj.core.data.Offset.offset(1.0e-9));
        assertThat(hybrid.mrr()).isCloseTo(0.9166666667d,
                org.assertj.core.data.Offset.offset(1.0e-9));
        assertThat(hybrid.recallAt2()).isGreaterThanOrEqualTo(lexical.recallAt2());
    }

    private static Metrics aggregate(List<Fixture> fixtures,
                                     Function<Fixture, List<SearchCandidate>> ranking) {
        double recall = 0.0d;
        double precision = 0.0d;
        double mrr = 0.0d;
        for (Fixture fixture : fixtures) {
            List<String> ranked = ranking.apply(fixture).stream()
                    .map(SearchCandidate::stableId).limit(2).toList();
            long hits = ranked.stream().filter(fixture.relevant()::contains).count();
            recall += (double) hits / fixture.relevant().size();
            precision += (double) hits / 2.0d;
            mrr += reciprocalRank(ranked, fixture.relevant());
        }
        int count = fixtures.size();
        return new Metrics(recall / count, precision / count, mrr / count);
    }

    private static double reciprocalRank(List<String> ranked, Set<String> relevant) {
        for (int index = 0; index < ranked.size(); index++) {
            if (relevant.contains(ranked.get(index))) {
                return 1.0d / (index + 1);
            }
        }
        return 0.0d;
    }

    private record Metrics(double recallAt2, double precisionAt2, double mrr) {
    }

    private record Fixture(String label, List<SearchCandidate> lexical,
                           List<SearchCandidate> vector, Set<String> relevant) {
        private static Fixture of(String label, List<String> lexical, List<String> vector,
                                  Set<String> relevant) {
            return new Fixture(label, candidates(lexical), candidates(vector),
                    Set.copyOf(new LinkedHashSet<>(relevant)));
        }
    }

    private static List<SearchCandidate> candidates(List<String> ids) {
        List<SearchCandidate> candidates = new java.util.ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            String id = ids.get(index);
            SearchResultKind kind = id.startsWith("source-") ? SearchResultKind.SOURCE_CHUNK
                    : SearchResultKind.WIKI;
            candidates.add(new SearchCandidate(kind, id, 100.0d - index, "",
                    new SearchWorkspaceProvenance(7L, "quality-fixture"),
                    kind == SearchResultKind.WIKI ? id : null, id, null, null, null,
                    "fixture-" + id, null, null, null, null, null, null, null, null, null));
        }
        return List.copyOf(candidates);
    }
}
