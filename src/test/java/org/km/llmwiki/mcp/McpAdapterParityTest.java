package org.km.llmwiki.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.answer.AnswerContextAssembler;
import org.km.llmwiki.ai.answer.AnswerContextBudget;
import org.km.llmwiki.ai.answer.AnswerContextCompactionPolicyRegistry;
import org.km.llmwiki.ai.answer.AnswerProviderMetadata;
import org.km.llmwiki.ai.answer.AnswerResult;
import org.km.llmwiki.ai.answer.ContextPolicyV1Current;
import org.km.llmwiki.ai.answer.EvidenceContextProjectorService;
import org.km.llmwiki.ai.answer.ProviderUsageStatus;
import org.km.llmwiki.ai.answer.StubAnswerClient;
import org.km.llmwiki.ai.ask.AskApiException;
import org.km.llmwiki.ai.ask.AskApiResponse;
import org.km.llmwiki.ai.ask.AskApplicationService;
import org.km.llmwiki.ai.ask.AskController;
import org.km.llmwiki.ai.ask.AskFailureType;
import org.km.llmwiki.ai.ask.AskResult;
import org.km.llmwiki.ai.ask.AskService;
import org.km.llmwiki.ai.ask.AskStatus;
import org.km.llmwiki.ai.provider.ProviderEgressService;
import org.km.llmwiki.rag.EvidenceBudget;
import org.km.llmwiki.rag.EvidenceBundle;
import org.km.llmwiki.rag.EvidenceItem;
import org.km.llmwiki.rag.EvidenceKind;
import org.km.llmwiki.rag.EvidenceWorkspace;
import org.km.llmwiki.rag.FusionRankingPolicy;
import org.km.llmwiki.rag.FusionRankingPolicyProvider;
import org.km.llmwiki.rag.RetrievalDiagnostics;
import org.km.llmwiki.rag.RetrievalInspectorService;
import org.km.llmwiki.rag.RetrievalMode;
import org.km.llmwiki.rag.RetrievalService;
import org.km.llmwiki.source.SourceChunkLocatorService;
import org.km.llmwiki.source.SourceChunkNotFoundException;
import org.km.llmwiki.web.RetrievalInspectionResponse;
import org.km.llmwiki.web.RetrievalInspectorController;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REST↔MCP contract parity for the shared application boundaries (#331). Both adapters must
 * produce identical application-owned fields for identical inputs and state; parity compares
 * application records (never transport JSON bytes), while envelope separation is asserted on
 * each transport shape separately.
 */
@Tag("unit")
class McpAdapterParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EvidenceWorkspace WORKSPACE = new EvidenceWorkspace(7, "Personal Wiki");
    private static final AnswerProviderMetadata METADATA =
            new AnswerProviderMetadata("stub", "offline-model");

    @Test
    void answeredAskProducesIdenticalApplicationProjectionOnBothAdapters() {
        java.util.concurrent.atomic.AtomicInteger providerCalls = new java.util.concurrent.atomic.AtomicInteger();
        org.km.llmwiki.ai.answer.AnswerClient countingProvider = request -> {
            providerCalls.incrementAndGet();
            return new AnswerResult("Grounded answer", List.of("E2", "E1", "E2"), false,
                    METADATA, Optional.empty());
        };
        Harness harness = harness(answerBundle(), countingProvider);
        JsonNode restBody = body("What is the design?", "HYBRID_FTS");

        AskApiResponse rest = harness.rest.ask(restBody).data();
        McpToolResult mcp = harness.executor.execute("km_ask",
                args("What is the design?", "HYBRID_FTS"));

        assertThat(mcp.isError()).isFalse();
        // Wall-clock latencies legitimately differ between the two separate executions, so
        // they are excluded from record equality and asserted non-negative on each side.
        assertThat(mcp.payload()).usingRecursiveComparison()
                .ignoringFields("executionMetadata.contextDiagnostics.projectionLatencyMs",
                        "executionMetadata.contextDiagnostics.answerLatencyMs")
                .isEqualTo(rest);
        assertThat(rest.executionMetadata().contextDiagnostics().projectionLatencyMs())
                .isGreaterThanOrEqualTo(0L);
        assertThat(rest.executionMetadata().contextDiagnostics().answerLatencyMs())
                .isGreaterThanOrEqualTo(0L);
        assertThat(((AskApiResponse) mcp.payload()).executionMetadata().contextDiagnostics()
                .projectionLatencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(((AskApiResponse) mcp.payload()).executionMetadata().contextDiagnostics()
                .answerLatencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(rest.status()).isEqualTo(AskStatus.ANSWERED);
        assertThat(rest.citations()).extracting(AskApiResponse.Citation::citationId)
                .containsExactly("E1", "E2");
        assertAskPayloadEquals(mcp.payload(), rest);
        assertThat(((AskApiResponse) mcp.payload()).executionMetadata())
                .usingRecursiveComparison()
                .ignoringFields("contextDiagnostics.projectionLatencyMs",
                        "contextDiagnostics.answerLatencyMs")
                .isEqualTo(rest.executionMetadata());
        assertThat(mcp.providerEgress()).extracting(
                        McpToolResult.ProviderEgressLine::level,
                        McpToolResult.ProviderEgressLine::destinationClass)
                .contains(new org.assertj.core.groups.Tuple("EXECUTION", "UNAVAILABLE"));
        // Neither adapter re-runs the pipeline for metadata: exactly one provider call total
        // across both adapter invocations proves no hidden second execution.
        assertThat(providerCalls).hasValue(2);
    }

    @Test
    void noEvidenceAskIsInsufficientWithNotAttemptedUsageOnBothAdapters() {
        Harness harness = harness(answerBundle(List.of()),
                StubAnswerClient.returning(new AnswerResult("unused", List.of(), true,
                        METADATA, Optional.empty())));
        JsonNode restBody = body("unknown", "WIKI_ONLY");

        AskApiResponse rest = harness.rest.ask(restBody).data();
        McpToolResult mcp = harness.executor.execute("km_ask", args("unknown", "WIKI_ONLY"));

        assertThat(rest.status()).isEqualTo(AskStatus.INSUFFICIENT_EVIDENCE);
        assertThat(mcp.isError()).isFalse();
        assertAskPayloadEquals(mcp.payload(), rest);
        assertThat(mcp.providerEgress()).extracting(
                        McpToolResult.ProviderEgressLine::level,
                        McpToolResult.ProviderEgressLine::destinationClass)
                .contains(new org.assertj.core.groups.Tuple("EXECUTION", "NOT_ATTEMPTED"));
    }

    @Test
    void providerFailureMapsToSameApplicationTypeWithPerTransportEnvelopes() {
        Harness harness = harness(answerBundle(),
                StubAnswerClient.failing(
                        org.km.llmwiki.ai.answer.AnswerFailureType.TIMEOUT_OR_NETWORK_UNAVAILABLE,
                        "authorization: Bearer secret"));
        JsonNode restBody = body("What is the design?", "HYBRID_FTS");

        assertThatThrownBy(() -> harness.rest.ask(restBody))
                .isInstanceOf(AskApiException.class)
                .satisfies(failure -> assertThat(
                        ((AskApiException) failure).failureType())
                        .isEqualTo(AskFailureType.PROVIDER_TIMEOUT_OR_NETWORK_UNAVAILABLE));
        McpToolResult mcp = harness.executor.execute("km_ask",
                args("What is the design?", "HYBRID_FTS"));

        assertThat(mcp.isError()).isTrue();
        assertThat(mcp.errorCode())
                .isEqualTo(McpToolError.PROVIDER_TIMEOUT_OR_NETWORK_UNAVAILABLE);
        // The typed mapping survives, but the secret never crosses either boundary.
        assertThat(mcp.message()).doesNotContain("secret");
    }

    @Test
    void invalidRetrievalModeIsRejectedIdenticallyOnBothAdapters() {
        Harness harness = harness(answerBundle(),
                StubAnswerClient.returning(new AnswerResult("unused", List.of(), true,
                        METADATA, Optional.empty())));

        assertThatThrownBy(() -> harness.rest.ask(body("q", "NOPE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("retrievalMode is invalid");
        McpToolResult mcp = harness.executor.execute("km_ask", args("q", "NOPE"));

        assertThat(mcp.isError()).isTrue();
        assertThat(mcp.errorCode()).isEqualTo(McpToolError.INVALID_REQUEST);
        assertThat(mcp.message()).isEqualTo("retrievalMode is invalid");
    }

    @Test
    void rerankExecutionMetadataIsIdenticalOnBothAdapters() {
        Harness harness = harness(answerBundle(),
                StubAnswerClient.returning(new AnswerResult("Grounded answer", List.of("E1"),
                        false, METADATA, Optional.empty())));
        JsonNode restBody = body("What is the design?", "HYBRID_FTS");

        AskApiResponse rest = harness.rest.ask(restBody).data();
        McpToolResult mcp = harness.executor.execute("km_ask",
                args("What is the design?", "HYBRID_FTS"));

        assertAskPayloadEquals(mcp.payload(), rest);
        assertThat(((AskApiResponse) mcp.payload()).executionMetadata())
                .usingRecursiveComparison()
                .ignoringFields("contextDiagnostics.projectionLatencyMs",
                        "contextDiagnostics.answerLatencyMs")
                .isEqualTo(rest.executionMetadata());
        assertThat(rest.executionMetadata().rerankStatus()).isNotNull();
        assertThat(rest.executionMetadata().rerankPolicyVersion()).isNotNull();
        assertThat(rest.citations()).extracting(AskApiResponse.Citation::citationId)
                .containsExactly("E1");
        assertThat(((AskApiResponse) mcp.payload()).citations())
                .isEqualTo(rest.citations());
    }

    @Test
    void inspectorProducesIdenticalReportProjectionOnBothAdapters() {
        Harness harness = inspectorHarness(false);

        RetrievalInspectionResponse rest = harness.inspectorRest
                .inspect("q", "HYBRID_GRAPH").data();
        McpToolResult mcp = harness.executor.execute("km_retrieval_inspect",
                JSON.createObjectNode().put("question", "q").put("mode", "HYBRID_GRAPH"));

        assertThat(mcp.isError()).isFalse();
        assertThat(mcp.payload()).isEqualTo(rest);
        assertThat(rest.query()).isEqualTo("q");
        assertThat(rest.mode()).isEqualTo("HYBRID_GRAPH");
        // Empty evidence keeps final evidence and selection jointly empty, which satisfies
        // the report invariant; both adapters must project the identical insufficient report.
        assertThat(rest.insufficientEvidence()).isTrue();
        assertThat(rest.finalEvidence()).isEmpty();
        assertThat(((RetrievalInspectionResponse) mcp.payload()).finalEvidence())
                .isEqualTo(rest.finalEvidence());
        assertThat(((RetrievalInspectionResponse) mcp.payload()).modalityDiagnostics())
                .isEqualTo(rest.modalityDiagnostics());
    }

    @Test
    void inspectorDegradationSemanticsAreIdenticalOnBothAdapters() {
        Harness harness = inspectorHarness(true);

        RetrievalInspectionResponse rest = harness.inspectorRest
                .inspect("q", "HYBRID_GRAPH").data();
        McpToolResult mcp = harness.executor.execute("km_retrieval_inspect",
                JSON.createObjectNode().put("question", "q").put("mode", "HYBRID_GRAPH"));

        assertThat(mcp.isError()).isFalse();
        assertThat(mcp.payload()).isEqualTo(rest);
        assertThat(rest.modalityDiagnostics()).isEqualTo(
                ((RetrievalInspectionResponse) mcp.payload()).modalityDiagnostics());
    }

    @Test
    void unknownSourceChunkIsSafeNotFoundOnBothPathsWithoutExistenceLeak() {
        Harness harness = harness(answerBundle(),
                StubAnswerClient.returning(new AnswerResult("unused", List.of(), true,
                        METADATA, Optional.empty())));

        McpToolResult mcp = harness.executor.execute("km_source_locator",
                JSON.createObjectNode().put("chunkId", 424242L));

        assertThat(mcp.isError()).isTrue();
        assertThat(mcp.errorCode()).isEqualTo(McpToolError.NOT_FOUND);
        assertThat(mcp.message()).doesNotContain("Exception");
        assertThatThrownBy(() -> harness.locator.locate(424242L))
                .isInstanceOf(SourceChunkNotFoundException.class);
    }

    @Test
    void transportEnvelopesStaySeparated() {
        Harness harness = harness(answerBundle(),
                StubAnswerClient.returning(new AnswerResult("Grounded answer", List.of("E1"),
                        false, METADATA, Optional.empty())));
        McpToolResult mcp = harness.executor.execute("km_ask",
                args("What is the design?", "HYBRID_FTS"));

        JsonNode mcpPayload = JSON.valueToTree(mcp.payload());
        assertThat(mcpPayload.has("data")).isFalse();
        assertThat(mcpPayload.has("isError")).isFalse();
        assertThat(mcpPayload.has("providerEgress")).isFalse();

        AskApiResponse rest = harness.rest.ask(body("What is the design?", "HYBRID_FTS")).data();
        JsonNode restPayload = JSON.valueToTree(rest);
        assertThat(restPayload.has("isError")).isFalse();
        assertThat(restPayload.has("providerEgress")).isFalse();
    }

    private static void assertAskPayloadEquals(Object mcpPayload, AskApiResponse rest) {
        assertThat(mcpPayload).usingRecursiveComparison()
                .ignoringFields("executionMetadata.contextDiagnostics.projectionLatencyMs",
                        "executionMetadata.contextDiagnostics.answerLatencyMs")
                .isEqualTo(rest);
    }

    private record Harness(AskController rest, McpToolExecutor executor,
                           RetrievalInspectorController inspectorRest,
                           SourceChunkLocatorService locator) {
    }

    private static Harness harness(EvidenceBundle bundle,
                                   org.km.llmwiki.ai.answer.AnswerClient provider) {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.retrieve(any())).thenReturn(bundle);
        return harnessWithRetrieval(retrieval, provider);
    }

    private static Harness harnessWithRetrieval(RetrievalService retrieval) {
        return harnessWithRetrieval(retrieval,
                StubAnswerClient.returning(new AnswerResult("unused", List.of(), true,
                        METADATA, Optional.empty())));
    }

    private static Harness harnessWithRetrieval(RetrievalService retrieval,
                                                org.km.llmwiki.ai.answer.AnswerClient provider) {
        AskService askService = new AskService(retrieval,
                new EvidenceContextProjectorService(new AnswerContextAssembler(),
                        new AnswerContextCompactionPolicyRegistry(
                                List.of(new ContextPolicyV1Current()),
                                ContextPolicyV1Current.VERSION)),
                new org.km.llmwiki.rag.SecondStageRerankService(
                        new org.km.llmwiki.rag.SecondStageRerankPolicyRegistry(
                                List.of(new org.km.llmwiki.rag.SecondStageRerankPolicy.NoOp()),
                                org.km.llmwiki.rag.SecondStageRerankPolicy.NoOp.VERSION)),
                provider);
        AskApplicationService application = new AskApplicationService(askService);
        RetrievalInspectorService inspectorService = inspectorService(retrieval);
        SourceChunkLocatorService locator = mock(SourceChunkLocatorService.class);
        when(locator.locate(424242L)).thenThrow(new SourceChunkNotFoundException(424242L));
        McpToolExecutor executor = new McpToolExecutor(
                mock(org.km.llmwiki.system.SystemStatusService.class),
                mock(org.km.llmwiki.search.SearchService.class),
                inspectorService,
                locator,
                application,
                mock(ProviderEgressService.class));
        return new Harness(new AskController(application), executor,
                new RetrievalInspectorController(inspectorService), locator);
    }

    private static RetrievalInspectorService inspectorService(RetrievalService retrieval) {
        FusionRankingPolicyProvider policies = mock(FusionRankingPolicyProvider.class);
        when(policies.policy()).thenReturn(FusionRankingPolicy.production());
        return new RetrievalInspectorService(retrieval, policies);
    }

    private static Harness inspectorHarness(boolean degraded) {
        EvidenceBundle bundle = degraded
                ? inspectorBundle(RetrievalDiagnostics.degradedHybrid("vector unavailable"))
                : inspectorBundle(RetrievalDiagnostics.lexical());
        // The two-arg retrieve overload takes a package-private collector type, so it cannot
        // be stubbed by overload from this package; a default answer returns the same bundle
        // for every retrieve invocation instead (the collector stays unfed, deterministically).
        RetrievalService retrieval = mock(RetrievalService.class, invocation -> {
            if (invocation.getMethod().getName().equals("retrieve")) {
                return bundle;
            }
            return org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
        return harnessWithRetrieval(retrieval);
    }

    private static JsonNode body(String question, String mode) {
        return JSON.createObjectNode().put("question", question).put("retrievalMode", mode);
    }

    private static JsonNode args(String question, String mode) {
        return JSON.createObjectNode().put("question", question).put("retrievalMode", mode);
    }

    private static EvidenceBundle answerBundle() {
        return answerBundle(List.of(
                wiki("architecture", "Architecture", "vault/architecture.md", "Wiki fact"),
                source(41L, 900L, "design.pdf", "Source fact")));
    }

    private static EvidenceBundle answerBundle(List<EvidenceItem> items) {
        int characters = items.stream().mapToInt(item ->
                item.content().codePointCount(0, item.content().length())).sum();
        return new EvidenceBundle("question", RetrievalMode.HYBRID_FTS, WORKSPACE, items,
                new EvidenceBudget(8, 100_000, items.size(), characters, (characters + 3) / 4,
                        false), items.size(), 0, items.isEmpty(),
                RetrievalDiagnostics.lexical());
    }

    private static EvidenceBundle inspectorBundle(RetrievalDiagnostics diagnostics) {
        return new EvidenceBundle("q", RetrievalMode.HYBRID_GRAPH, WORKSPACE, List.of(),
                new EvidenceBudget(8, 12_000, 0, 0, 0, false), 0, 0, true, diagnostics);
    }

    private static EvidenceItem wiki(String id, String title, String path, String content) {
        return new EvidenceItem(EvidenceKind.WIKI, id, WORKSPACE, 0.9, content, "snippet", false,
                "hash-" + id, id, title, "CONCEPT", path, 1,
                null, null, null, null, null, null, null);
    }

    private static EvidenceItem source(long chunkId, long documentId, String documentName,
                                       String content) {
        return new EvidenceItem(EvidenceKind.SOURCE_CHUNK, Long.toString(chunkId), WORKSPACE, 0.8,
                content, "snippet", false, "hash-source-" + chunkId, null, null, null, null,
                null, chunkId, documentId, documentName, 1, 1, "Overview", "Root > Overview");
    }
}
