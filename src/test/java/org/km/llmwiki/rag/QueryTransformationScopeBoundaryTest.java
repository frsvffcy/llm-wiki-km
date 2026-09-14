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
 * Query-transformation scope proof (#390): the production Ask/retrieval path must not contain
 * any semantic query rewriting / multi-query / HyDE boundary. The only query-side
 * transformation in production is the deterministic lexical projection chain
 * (validation/NFC → {@code cjk-bigram-v1} at the FTS boundary, bare NFC for the embedding
 * input); semantic rewriting is an evaluation-only candidate in test sources and can only
 * enter production through a dedicated adoption issue. Source-level boundary check mirrors
 * the Ask read-only boundary test (#374).
 */
@Tag("unit")
class QueryTransformationScopeBoundaryTest {

    private static final Path PRODUCTION_ROOT = Path.of("src/main/java/org/km/llmwiki");

    private static final List<String> FORBIDDEN_BOUNDARY_TOKENS = List.of(
            "QueryRewrite", "queryRewrite", "QueryTransformation", "QueryExpansion",
            "MultiQuery", "multiQueryFanOut", "HyDE", "HypotheticalDocument",
            "hypotheticalDocument", "SemanticQueryPlanner");

    @Test
    void productionQueryPathContainsNoSemanticRewriteBoundary() throws Exception {
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
                .as("production must not grow a semantic query-rewrite boundary outside a "
                        + "dedicated adoption issue (#390 decision gate)")
                .isEmpty();
    }

    @Test
    void askServiceHandsTheQuestionToRetrievalWithoutAnyQueryStage() throws Exception {
        String askService = Files.readString(
                Path.of("src/main/java/org/km/llmwiki/ai/ask/AskService.java"));
        assertThat(askService).isNotBlank();
        // The production stage order is fixed: retrieval on the request's own question, then
        // second-stage rerank over the qualified bundle, then context projection. No query
        // transformation stage may appear between the question and retrieval.
        assertThat(askService).contains("retrievalService.retrieve(request.retrievalRequest())");
        assertThat(askService).contains("rerankService.apply(");
        int retrieveAt = askService.indexOf("retrievalService.retrieve(request.retrievalRequest())");
        int rerankAt = askService.indexOf("rerankService.apply(");
        int projectAt = askService.indexOf("contextProjector.project(");
        assertThat(rerankAt).isGreaterThan(retrieveAt);
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
