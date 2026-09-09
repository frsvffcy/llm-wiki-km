package org.km.llmwiki.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.rag.EvidenceBudget;
import org.km.llmwiki.rag.EvidenceWorkspace;
import org.km.llmwiki.rag.FusedModalityDiagnostics;
import org.km.llmwiki.rag.ModalityOutcome;
import org.km.llmwiki.rag.RetrievalInspectionReport;
import org.km.llmwiki.rag.RetrievalInspectionTrace;
import org.km.llmwiki.rag.RetrievalMode;
import org.km.llmwiki.rag.RetrievalRequest;
import org.km.llmwiki.testsupport.SpringIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@WebMvcTest(RetrievalInspectorController.class)
@Import(GlobalExceptionHandler.class)
class RetrievalInspectorApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private org.km.llmwiki.rag.RetrievalInspectorService inspectorService;

    @Test
    void inspectsWithModeAndQuestionAndProjectsOnlySafeFields() throws Exception {
        when(inspectorService.inspect(any())).thenReturn(report());

        mockMvc.perform(get("/api/v1/retrieval/inspect")
                        .queryParam("question", "  為什麼設計成這樣？ ")
                        .queryParam("mode", "HYBRID_GRAPH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.query").value("為什麼設計成這樣？"))
                .andExpect(jsonPath("$.data.mode").value("HYBRID_GRAPH"))
                .andExpect(jsonPath("$.data.strategy").value("FUSED"))
                .andExpect(jsonPath("$.data.fusionPolicyVersion")
                        .value("fusion-rrf-v2-graph-damped"))
                .andExpect(jsonPath("$.data.modalities[0].modality").value("LEXICAL"))
                .andExpect(jsonPath("$.data.modalities[0].outcome").value("CONTRIBUTED"))
                .andExpect(jsonPath("$.data.modalities[0].candidates[0].identity")
                        .value("WIKI:1"))
                .andExpect(jsonPath("$.data.modalities[0].candidates[0].ordinal").value(1))
                .andExpect(jsonPath("$.data.modalities[0].rejected[0].identity")
                        .value("WIKI:2"))
                .andExpect(jsonPath("$.data.modalities[0].rejected[0].reason")
                        .value("STALE_REVISION"))
                .andExpect(jsonPath("$.data.fusedOrder[0]").value("WIKI:1"))
                .andExpect(jsonPath("$.data.selection[0].disposition").value("SELECTED"))
                .andExpect(jsonPath("$.data.selection[1].disposition").value("REJECTED"))
                .andExpect(jsonPath("$.data.selection[1].reason").value("STALE_REVISION"))
                .andExpect(jsonPath("$.data.finalEvidence[0].identity").value("WIKI:1"))
                .andExpect(jsonPath("$.data.modalityDiagnostics.graph").value("CONTRIBUTED"))
                .andExpect(jsonPath("$.data.budget.maxItems").value(8))
                .andExpect(jsonPath("$.data.searchedCandidateCount").value(5))
                .andExpect(jsonPath("$.data.insufficientEvidence").value(false));

        verify(inspectorService).inspect(argThat((RetrievalRequest request) ->
                request.query().equals("為什麼設計成這樣？")
                        && request.mode() == RetrievalMode.HYBRID_GRAPH));
    }

    @Test
    void unknownModeAndBlankQuestionFailAsInvalidRequest() throws Exception {
        mockMvc.perform(get("/api/v1/retrieval/inspect")
                        .queryParam("question", "q")
                        .queryParam("mode", "NOT_A_MODE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/v1/retrieval/inspect")
                        .queryParam("question", "   ")
                        .queryParam("mode", "WIKI_ONLY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void missingQuestionIsABadRequestWithoutServiceInteraction() throws Exception {
        mockMvc.perform(get("/api/v1/retrieval/inspect")
                        .queryParam("mode", "WIKI_ONLY"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rawScoresVendorIdentifiersAndExceptionDetailsNeverAppear() throws Exception {
        when(inspectorService.inspect(any())).thenReturn(report());

        String body = mockMvc.perform(get("/api/v1/retrieval/inspect")
                        .queryParam("question", "q")
                        .queryParam("mode", "HYBRID_GRAPH"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("similarity", "fingerprint", "snapshot", "Exception",
                        "at org.km")
                .doesNotContainPattern("#\\d+:\\d+")
                .doesNotContainPattern("\\b0\\.\\d+");
    }

    private static RetrievalInspectionReport report() {
        return new RetrievalInspectionReport(
                "為什麼設計成這樣？",
                RetrievalMode.HYBRID_GRAPH,
                org.km.llmwiki.rag.RetrievalStrategy.FUSED,
                new EvidenceWorkspace(7L, "ws"),
                "fusion-rrf-v2-graph-damped",
                List.of(new RetrievalInspectionReport.ModalitySection(
                        org.km.llmwiki.rag.CandidateSignal.LEXICAL, ModalityOutcome.CONTRIBUTED,
                        List.of(new RetrievalInspectionTrace.CandidateTrace("WIKI:1", 1)),
                        List.of(new RetrievalInspectionTrace.RejectedTrace("WIKI:2",
                                "STALE_REVISION")))),
                List.of("WIKI:1", "WIKI:2"),
                List.of(RetrievalInspectionTrace.SelectionTrace.selected("WIKI:1"),
                        RetrievalInspectionTrace.SelectionTrace.rejected("WIKI:2",
                                "STALE_REVISION")),
                List.of(new RetrievalInspectionReport.FinalEvidence(1, "WIKI:1")),
                Map.of("WIKI:1", Set.of(org.km.llmwiki.rag.CandidateSignal.LEXICAL)),
                new FusedModalityDiagnostics(ModalityOutcome.CONTRIBUTED,
                        ModalityOutcome.CONTRIBUTED, ModalityOutcome.CONTRIBUTED,
                        null, null, 1),
                5, 1, false,
                new EvidenceBudget(8, 12_000, 1, 100, 25, false));
    }
}
