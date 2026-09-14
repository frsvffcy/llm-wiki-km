package org.km.llmwiki.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Query-transformation production boundary (#401): semantic rewriting is limited to one
 * versioned single-rewrite stage. Multi-query, HyDE, and planner boundaries remain excluded.
 */
@Tag("unit")
class QueryTransformationScopeBoundaryTest {

    private static final Path PRODUCTION_ROOT = Path.of("src/main/java/org/km/llmwiki");

    private static final List<String> FORBIDDEN_BOUNDARY_TOKENS = List.of(
            "QueryExpansion", "MultiQuery", "multiQueryFanOut", "HyDE", "HypotheticalDocument",
            "hypotheticalDocument", "SemanticQueryPlanner");

    @Test
    void productionQueryPathContainsNoUnadoptedQueryExpansionBoundary() throws Exception {
        List<Path> sources;
        try (Stream<Path> paths = Files.walk(PRODUCTION_ROOT)) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
        assertThat(sources).isNotEmpty();
        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            String text = Files.readString(source);
            for (String token : FORBIDDEN_BOUNDARY_TOKENS) {
                if (text.contains(token)) {
                    offenders.add(source + " contains " + token);
                }
            }
        }
        assertThat(offenders)
                .as("#401 permits one rewrite only; expansion, HyDE, and planners remain excluded")
                .isEmpty();
    }

    @Test
    void askServiceKeepsOriginalRetrievalBeforeTransformationRerankAndProjection() throws Exception {
        String askService = Files.readString(
                Path.of("src/main/java/org/km/llmwiki/ai/ask/AskService.java"));
        assertThat(askService).isNotBlank();
        // The production stage order is fixed: original retrieval first, bounded optional
        // transformation second, then rerank and context projection.
        assertThat(askService).contains("retrievalService.retrieve(request.retrievalRequest())");
        assertThat(askService).contains("queryTransformationService.apply(");
        assertThat(askService).contains("rerankService.apply(");
        int retrieveAt = askService.indexOf("retrievalService.retrieve(request.retrievalRequest())");
        int transformAt = askService.indexOf("queryTransformationService.apply(");
        int rerankAt = askService.indexOf("rerankService.apply(");
        int projectAt = askService.indexOf("contextProjector.project(");
        assertThat(transformAt).isGreaterThan(retrieveAt);
        assertThat(rerankAt).isGreaterThan(transformAt);
        assertThat(projectAt).isGreaterThan(rerankAt);
    }

    @Test
    void theOnlyProductionQueryProjectionIsTheDeterministicCjkBigramChain() throws Exception {
        String projector = Files.readString(
                Path.of("src/main/java/org/km/llmwiki/search/CjkBigramProjector.java"));
        String ftsBoundary = Files.readString(
                Path.of("src/main/java/org/km/llmwiki/search/FtsMatchQuery.java"));
        assertThat(projector).contains("\"cjk-bigram-v1\"");
        assertThat(ftsBoundary).contains("CjkBigramProjector.tokens");
        assertThat(ftsBoundary).contains("Normalizer.Form.NFC");
        // The FTS query boundary is deterministic and provider-free: no LLM/embedding/provider
        // client may participate in producing the MATCH expression.
        assertThat(projector).doesNotContain("EmbeddingClient", "LlmClient", "AnswerClient",
                "ProviderEndpoint");
        assertThat(ftsBoundary).doesNotContain("EmbeddingClient", "LlmClient", "AnswerClient",
                "ProviderEndpoint");
    }
}
