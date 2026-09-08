package org.km.llmwiki.ai.ask;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.km.llmwiki.ai.answer.AnswerContextProvenance;
import org.km.llmwiki.ai.answer.AnswerProviderMetadata;
import org.km.llmwiki.rag.EvidenceKind;
import org.km.llmwiki.rag.RetrievalMode;
import org.km.llmwiki.testsupport.SpringIntegrationTest;
import org.km.llmwiki.web.GlobalExceptionHandler;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@WebMvcTest(AskController.class)
@Import(GlobalExceptionHandler.class)
class AskApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AskService askService;

    @BeforeEach
    void resetMock() {
        reset(askService);
    }

    @Test
    void returnsGroundedAnswerWithDisplayableWikiAndSourceCitations() throws Exception {
        AskCitation wiki = new AskCitation("E1", EvidenceKind.WIKI, "WIKI:architecture",
                "hash-wiki", new AnswerContextProvenance.Wiki(
                "Architecture", "vault/architecture.md", 4));
        AskCitation source = new AskCitation("E2", EvidenceKind.SOURCE_CHUNK, "SOURCE_CHUNK:41",
                "hash-source", new AnswerContextProvenance.Source(
                "design.pdf", 900L, 41L, 2, 8, "摘要", "第一章 > 摘要"));
        when(askService.ask(any())).thenReturn(answered(wiki, source));

        mockMvc.perform(post("/api/v1/ask")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"question":"What is the design?","retrievalMode":"HYBRID_FTS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ANSWERED"))
                .andExpect(jsonPath("$.data.answer").value("Grounded answer"))
                .andExpect(jsonPath("$.data.insufficientEvidence").value(false))
                .andExpect(jsonPath("$.data.citations[0].citationId").value("E1"))
                .andExpect(jsonPath("$.data.citations[0].provenance.type").value("WIKI"))
                .andExpect(jsonPath("$.data.citations[0].provenance.title").value("Architecture"))
                .andExpect(jsonPath("$.data.citations[0].provenance.path")
                        .value("vault/architecture.md"))
                .andExpect(jsonPath("$.data.citations[1].provenance.type").value("SOURCE"))
                .andExpect(jsonPath("$.data.citations[1].provenance.documentName")
                        .value("design.pdf"))
                .andExpect(jsonPath("$.data.citations[1].provenance.pageNo").value(8))
                .andExpect(jsonPath("$.data.citations[0].contentHash").doesNotExist())
                .andExpect(jsonPath("$.data.retrievalMetadata.strategy").value("LEXICAL"))
                .andExpect(jsonPath("$.data.retrievalMetadata.vectorSignalUsed").value(false))
                .andExpect(content().string(not(containsString("hash-wiki"))))
                .andExpect(content().string(not(containsString("raw prompt"))));

        verify(askService).ask(any(AskRequest.class));
    }

    @Test
    void returnsInsufficientEvidenceAsAValidNonProviderResult() throws Exception {
        when(askService.ask(any())).thenReturn(new AskResult(AskStatus.INSUFFICIENT_EVIDENCE,
                Optional.empty(), List.of(), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), new AskExecutionMetadata(0, 0, 0, false)));

        mockMvc.perform(post("/api/v1/ask")
                        .contentType(APPLICATION_JSON)
                        .content("{\"question\":\"unknown\",\"retrievalMode\":\"WIKI_ONLY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.data.insufficientEvidence").value(true))
                .andExpect(jsonPath("$.data.answer").doesNotExist())
                .andExpect(jsonPath("$.data.citations").isEmpty())
                .andExpect(jsonPath("$.data.providerMetadata").doesNotExist());
    }

    @Test
    void rejectsBlankOversizedUnicodeAndInvalidRetrievalRequestsBeforeApplicationService() throws Exception {
        mockMvc.perform(post("/api/v1/ask").contentType(APPLICATION_JSON)
                        .content("{\"question\":\"   \",\"retrievalMode\":\"WIKI_ONLY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(askService);

        mockMvc.perform(post("/api/v1/ask").contentType(APPLICATION_JSON)
                        .content("{\"question\":\"" + "中".repeat(4_001)
                                + "\",\"retrievalMode\":\"WIKI_ONLY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(askService);

        mockMvc.perform(post("/api/v1/ask").contentType(APPLICATION_JSON)
                        .content("{\"question\":\"question\",\"retrievalMode\":\"ARBITRARY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(askService);
    }

    @Test
    void rejectsProviderAndFilesystemFieldsAtTheHttpBoundary() throws Exception {
        mockMvc.perform(post("/api/v1/ask").contentType(APPLICATION_JSON)
                        .content("""
                                {"question":"question","retrievalMode":"WIKI_ONLY",
                                 "rawPrompt":"secret prompt","providerApiKey":"sk-test-secret",
                                 "filesystemPath":"/private/vault"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(askService);
    }

    @Test
    void preservesNoActiveWorkspaceContract() throws Exception {
        when(askService.ask(any())).thenThrow(new NoActiveWorkspaceException());

        mockMvc.perform(post("/api/v1/ask").contentType(APPLICATION_JSON)
                        .content("{\"question\":\"question\",\"retrievalMode\":\"WIKI_ONLY\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_WORKSPACE"));
    }

    @Test
    void mapsRetrievalUnavailableWithoutProviderOrDiagnosticDetails() throws Exception {
        when(askService.ask(any())).thenReturn(failed(AskFailureType.RETRIEVAL_UNAVAILABLE,
                "database password=secret; full prompt=raw prompt"));

        mockMvc.perform(post("/api/v1/ask").contentType(APPLICATION_JSON)
                        .content("{\"question\":\"question\",\"retrievalMode\":\"WIKI_ONLY\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("RETRIEVAL_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").value("Retrieval service is unavailable"))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("raw prompt"))));
    }

    @Test
    void mapsVectorUnavailableAsDistinctServiceUnavailableCode() throws Exception {
        when(askService.ask(any())).thenReturn(failed(AskFailureType.RETRIEVAL_VECTOR_UNAVAILABLE,
                "native vector path / secret"));

        mockMvc.perform(post("/api/v1/ask").contentType(APPLICATION_JSON)
                        .content("{\"question\":\"question\",\"retrievalMode\":\"SEMANTIC_WIKI\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("RETRIEVAL_VECTOR_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").value("Semantic retrieval is unavailable"))
                .andExpect(content().string(not(containsString("native vector"))));
    }

    @ParameterizedTest
    @EnumSource(value = AskFailureType.class, names = {
            "PROVIDER_CONFIGURATION_UNAVAILABLE",
            "PROVIDER_AUTHENTICATION_OR_AUTHORIZATION",
            "PROVIDER_RATE_LIMIT_OR_QUOTA",
            "PROVIDER_TIMEOUT_OR_NETWORK_UNAVAILABLE",
            "PROVIDER_SERVER_FAILURE",
            "PROVIDER_INVALID_RESPONSE",
            "LOCAL_VALIDATION"
    })
    void mapsEveryTypedProviderFailureToStableSafeEnvelope(AskFailureType type) throws Exception {
        when(askService.ask(any())).thenReturn(failed(type,
                "authorization: Bearer sk-test-secret; prompt=private evidence"));

        var result = mockMvc.perform(post("/api/v1/ask").contentType(APPLICATION_JSON)
                        .content("{\"question\":\"question\",\"retrievalMode\":\"WIKI_ONLY\"}"))
                .andExpect(status().is(type == AskFailureType.LOCAL_VALIDATION ? 400
                        : type == AskFailureType.PROVIDER_INVALID_RESPONSE ? 502 : 503))
                .andExpect(jsonPath("$.error.code").value(type.publicCode()))
                .andExpect(content().string(not(containsString("sk-test-secret"))))
                .andExpect(content().string(not(containsString("private evidence"))))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .doesNotContain("authorization");
    }

    @Test
    void graphGroundedModeIsAcceptedAndSurfacesGraphSignalMetadata() throws Exception {
        AskCitation wiki = new AskCitation("E1", EvidenceKind.WIKI, "WIKI:architecture",
                "hash-wiki", new AnswerContextProvenance.Wiki(
                "Architecture", "vault/architecture.md", 4));
        when(askService.ask(any())).thenReturn(new AskResult(AskStatus.ANSWERED,
                Optional.of("Grounded answer"), List.of(wiki), List.of(wiki),
                Optional.of(new AnswerProviderMetadata("stub", "offline-model")),
                Optional.empty(), Optional.empty(), new AskExecutionMetadata(2, 2, 32, false),
                org.km.llmwiki.rag.RetrievalDiagnostics.fused()));

        mockMvc.perform(post("/api/v1/ask").contentType(APPLICATION_JSON)
                        .content("""
                                {"question":"What is the design?","retrievalMode":"HYBRID_GRAPH"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ANSWERED"))
                .andExpect(jsonPath("$.data.retrievalMetadata.strategy").value("FUSED"))
                .andExpect(jsonPath("$.data.retrievalMetadata.graphSignalUsed").value(true))
                .andExpect(jsonPath("$.data.retrievalMetadata.graphDegraded").value(false))
                .andExpect(jsonPath("$.data.retrievalMetadata.graphUnavailable").value(false));

        verify(askService).ask(org.mockito.ArgumentMatchers.argThat(
                request -> request.retrievalMode() == RetrievalMode.HYBRID_GRAPH));
    }

    @Test
    void graphDegradedBaselineStillAnswersWithValidCitationsAndTypedDiagnostics() throws Exception {
        // Graph degraded + lexical/vector evidence sufficient must stay a normal ANSWERED
        // response with citations; degradation is typed metadata, never a forced failure.
        AskCitation wiki = new AskCitation("E1", EvidenceKind.WIKI, "WIKI:architecture",
                "hash-wiki", new AnswerContextProvenance.Wiki(
                "Architecture", "vault/architecture.md", 4));
        when(askService.ask(any())).thenReturn(new AskResult(AskStatus.ANSWERED,
                Optional.of("Baseline answer"), List.of(wiki), List.of(wiki),
                Optional.of(new AnswerProviderMetadata("stub", "offline-model")),
                Optional.empty(), Optional.empty(), new AskExecutionMetadata(1, 1, 16, false),
                new org.km.llmwiki.rag.RetrievalDiagnostics(
                        org.km.llmwiki.rag.RetrievalStrategy.FUSED, true, true, false, false,
                        null, true, true, false, "internal handoff drift detail")));

        mockMvc.perform(post("/api/v1/ask").contentType(APPLICATION_JSON)
                        .content("{\"question\":\"question\",\"retrievalMode\":\"HYBRID_GRAPH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ANSWERED"))
                .andExpect(jsonPath("$.data.answer").value("Baseline answer"))
                .andExpect(jsonPath("$.data.insufficientEvidence").value(false))
                .andExpect(jsonPath("$.data.citations[0].citationId").value("E1"))
                .andExpect(jsonPath("$.data.retrievalMetadata.graphDegraded").value(true))
                .andExpect(jsonPath("$.data.retrievalMetadata.graphUnavailable").value(false))
                .andExpect(content().string(not(containsString("internal handoff drift detail"))))
                .andExpect(content().string(not(containsString("hash-wiki"))));
    }

    @Test
    void omittedRetrievalModeIsRejectedWithoutDefaultingToAnyMode() throws Exception {
        // Invariant: the request boundary owns no mode defaulting policy — omitting the mode
        // must stay a client error instead of silently selecting the graph-grounded mode.
        mockMvc.perform(post("/api/v1/ask").contentType(APPLICATION_JSON)
                        .content("{\"question\":\"question\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(askService);
    }

    @Test
    void graphUnavailableBaselineStillAnswersWithValidCitationsWithoutInternalDetails()
            throws Exception {
        // Graph operational degradation + sufficient lexical/vector baseline stays ANSWERED
        // with valid citations; the unavailable signal is typed metadata, never a failure or
        // an insufficient-evidence masquerade.
        AskCitation wiki = new AskCitation("E1", EvidenceKind.WIKI, "WIKI:architecture",
                "hash-wiki", new AnswerContextProvenance.Wiki(
                "Architecture", "vault/architecture.md", 4));
        when(askService.ask(any())).thenReturn(new AskResult(AskStatus.ANSWERED,
                Optional.of("Baseline answer"), List.of(wiki), List.of(wiki),
                Optional.of(new AnswerProviderMetadata("stub", "offline-model")),
                Optional.empty(), Optional.empty(), new AskExecutionMetadata(1, 1, 16, false),
                new org.km.llmwiki.rag.RetrievalDiagnostics(
                        org.km.llmwiki.rag.RetrievalStrategy.FUSED, true, true, false, false,
                        null, false, false, true, "graph infrastructure failure")));

        mockMvc.perform(post("/api/v1/ask").contentType(APPLICATION_JSON)
                        .content("{\"question\":\"question\",\"retrievalMode\":\"HYBRID_GRAPH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ANSWERED"))
                .andExpect(jsonPath("$.data.answer").value("Baseline answer"))
                .andExpect(jsonPath("$.data.citations[0].citationId").value("E1"))
                .andExpect(jsonPath("$.data.retrievalMetadata.graphSignalUsed").value(false))
                .andExpect(jsonPath("$.data.retrievalMetadata.graphUnavailable").value(true))
                .andExpect(jsonPath("$.data.retrievalMetadata.graphDegraded").value(false))
                .andExpect(content().string(not(containsString("graph infrastructure failure"))))
                .andExpect(content().string(not(containsString("hash-wiki"))));
    }

    @Test
    void degradedGraphSignalStaysVisibleToTheBrowserWithoutInternalDetails() throws Exception {
        when(askService.ask(any())).thenReturn(new AskResult(AskStatus.INSUFFICIENT_EVIDENCE,
                Optional.empty(), List.of(), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), new AskExecutionMetadata(0, 0, 0, false),
                new org.km.llmwiki.rag.RetrievalDiagnostics(
                        org.km.llmwiki.rag.RetrievalStrategy.FUSED, true, true, false, false,
                        null, true, true, false, "internal handoff drift detail")));

        mockMvc.perform(post("/api/v1/ask").contentType(APPLICATION_JSON)
                        .content("{\"question\":\"unknown\",\"retrievalMode\":\"HYBRID_GRAPH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.data.retrievalMetadata.graphDegraded").value(true))
                .andExpect(content().string(not(containsString("internal handoff drift detail"))));
    }

    private static AskResult answered(AskCitation... citations) {
        return new AskResult(AskStatus.ANSWERED, Optional.of("Grounded answer"),
                List.of(citations), List.of(citations),
                Optional.of(new AnswerProviderMetadata("stub", "offline-model")), Optional.empty(),
                Optional.empty(), new AskExecutionMetadata(2, 2, 32, false));
    }

    private static AskResult failed(AskFailureType type, String diagnostic) {
        AskFailure failure = type == AskFailureType.RETRIEVAL_UNAVAILABLE
                || type == AskFailureType.RETRIEVAL_VECTOR_UNAVAILABLE
                ? new AskFailure(type, diagnostic,
                Optional.of(org.km.llmwiki.rag.RetrievalUnavailableException.Dependency.SEARCH_INDEX))
                : new AskFailure(type, diagnostic);
        return new AskResult(AskStatus.FAILED, Optional.empty(), List.of(), List.of(),
                Optional.empty(), Optional.empty(), Optional.of(failure),
                new AskExecutionMetadata(1, 1, 8, false));
    }
}
