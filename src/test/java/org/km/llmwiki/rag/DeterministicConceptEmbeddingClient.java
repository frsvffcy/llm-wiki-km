package org.km.llmwiki.rag;

import org.km.llmwiki.ai.embedding.EmbeddingClient;
import org.km.llmwiki.ai.embedding.EmbeddingProviderMetadata;
import org.km.llmwiki.ai.embedding.EmbeddingResult;
import org.km.llmwiki.ai.embedding.EmbeddingVector;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic offline embedding fixture for the retrieval quality benchmark.
 *
 * <p>Semantic similarity is modeled as concept-slot overlap over a fixed synonym vocabulary:
 * cross-language or paraphrased surface forms that name the same concept map to the same unit
 * slot, while unrelated concepts stay orthogonal. This keeps the quality gate fully offline and
 * reproducible (no remote provider, no network) while still exercising the production vector
 * path end to end: real SQLite KNN ordering, projection freshness/authority revalidation, and
 * the fusion contract. It is a fixture, not a production embedding model; corpus scenarios that
 * must not be reachable through the vector channel are simply not embedded at all.
 */
final class DeterministicConceptEmbeddingClient implements EmbeddingClient {

    static final String PROVIDER = "quality-fixture";
    static final String MODEL = "concept-v1";
    static final int DIMENSION = 12;
    static final String PROJECTION_VERSION =
            org.km.llmwiki.search.embedding.EmbeddingProjectionContract.VERSION;

    /** Fixed synonym groups; insertion order fixes the slot index and must never be reordered. */
    private static final Map<String, List<String>> CONCEPTS = buildConcepts();

    private static Map<String, List<String>> buildConcepts() {
        Map<String, List<String>> concepts = new LinkedHashMap<>();
        concepts.put("database", List.of("資料庫", "database", "db"));
        concepts.put("index", List.of("索引", "index"));
        concepts.put("cache", List.of("快取", "cache"));
        concepts.put("distributed", List.of("分散式", "distributed"));
        concepts.put("lock", List.of("鎖", "lock"));
        concepts.put("search", List.of("搜尋", "search"));
        concepts.put("concurrency", List.of("併發", "concurrency"));
        concepts.put("security", List.of("安全", "security", "auth"));
        concepts.put("cooking", List.of("烹飪", "cooking"));
        concepts.put("travel", List.of("旅行", "travel"));
        concepts.put("sport", List.of("運動", "sport"));
        concepts.put("design", List.of("設計", "design"));
        return Collections.unmodifiableMap(concepts);
    }

    List<Double> embedText(String text) {
        double[] values = new double[DIMENSION];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        int slot = 0;
        boolean any = false;
        for (List<String> terms : CONCEPTS.values()) {
            for (String term : terms) {
                if (normalized.contains(term.toLowerCase(Locale.ROOT))) {
                    values[slot] = 1.0d;
                    any = true;
                    break;
                }
            }
            slot++;
        }
        if (!any) {
            throw new IllegalStateException(
                    "Benchmark fixture text must contain at least one known concept: " + text);
        }
        return Arrays.stream(values).boxed().toList();
    }

    @Override
    public EmbeddingResult embed(org.km.llmwiki.ai.embedding.EmbeddingRequest request) {
        return new EmbeddingResult(request.inputs().stream()
                .map(input -> new EmbeddingVector(input.identity(), embedText(input.text())))
                .toList(),
                new EmbeddingProviderMetadata(PROVIDER, MODEL),
                java.util.Optional.empty());
    }
}
