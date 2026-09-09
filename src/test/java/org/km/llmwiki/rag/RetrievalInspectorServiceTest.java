package org.km.llmwiki.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
class RetrievalInspectorServiceTest {

    private final FusionRankingPolicyProvider policyProvider = mock(
            FusionRankingPolicyProvider.class);

    @Test
    void fusedInspectionCarriesPolicyVersionTraceAndFinalHandoffOrder() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        doAnswer(invocation -> {
            RetrievalInspectionCollector collector = invocation.getArgument(1);
            collector.channelCandidate(CandidateSignal.LEXICAL, "WIKI:1");
            collector.channelCandidate(CandidateSignal.VECTOR, "SOURCE_CHUNK:7");
            collector.fusedOrder(List.of("SOURCE_CHUNK:7", "WIKI:1"));
            collector.itemModalities(Map.of("SOURCE_CHUNK:7",
                    Set.of(CandidateSignal.LEXICAL, CandidateSignal.VECTOR)));
            collector.selected("SOURCE_CHUNK:7");
            collector.selected("WIKI:1");
            return bundle("HYBRID_GRAPH", item("WIKI", "1"), item("SOURCE_CHUNK", "7"));
        }).when(retrievalService).retrieve(any(), any());
        when(policyProvider.policy()).thenReturn(FusionRankingPolicy.production());
        RetrievalInspectorService service = new RetrievalInspectorService(retrievalService,
                policyProvider);

        RetrievalInspectionReport report = service.inspect(
                RetrievalRequest.defaults("q", RetrievalMode.HYBRID_GRAPH));

        assertThat(report.fusionPolicyVersion()).isEqualTo("fusion-rrf-v2-graph-damped");
        assertThat(report.fusedOrder()).containsExactly("SOURCE_CHUNK:7", "WIKI:1");
        assertThat(report.finalEvidence()).extracting(
                        RetrievalInspectionReport.FinalEvidence::ordinal,
                        RetrievalInspectionReport.FinalEvidence::identity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "WIKI:1"),
                        org.assertj.core.groups.Tuple.tuple(2, "SOURCE_CHUNK:7"));
        assertThat(report.itemModalities().get("SOURCE_CHUNK:7"))
                .containsExactlyInAnyOrder(CandidateSignal.LEXICAL, CandidateSignal.VECTOR);
        assertThat(report.selection()).extracting(
                        RetrievalInspectionTrace.SelectionTrace::disposition)
                .containsOnly(RetrievalInspectionTrace.Disposition.SELECTED);
    }

    @Test
    void nonFusedInspectionHasNoFusionPolicyVersionAndDisabledGraphSignal() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        doAnswer(invocation -> {
            RetrievalInspectionCollector collector = invocation.getArgument(1);
            collector.channelCandidate(CandidateSignal.LEXICAL, "WIKI:1");
            collector.selected("WIKI:1");
            return bundle("WIKI_ONLY", item("WIKI", "1"));
        }).when(retrievalService).retrieve(any(), any());
        RetrievalInspectorService service = new RetrievalInspectorService(retrievalService,
                policyProvider);

        RetrievalInspectionReport report = service.inspect(
                RetrievalRequest.defaults("q", RetrievalMode.WIKI_ONLY));

        assertThat(report.fusionPolicyVersion()).isNull();
        assertThat(report.fusedOrder()).isEmpty();
        assertThat(report.modalityDiagnostics().lexical()).isEqualTo(ModalityOutcome.CONTRIBUTED);
        assertThat(report.modalityDiagnostics().vector()).isEqualTo(ModalityOutcome.DISABLED);
        assertThat(report.modalityDiagnostics().graph()).isEqualTo(ModalityOutcome.DISABLED);
        assertThat(report.modalities()).singleElement().satisfies(section -> {
            assertThat(section.modality()).isEqualTo(CandidateSignal.LEXICAL);
            assertThat(section.outcome()).isEqualTo(ModalityOutcome.CONTRIBUTED);
        });
    }

    @Test
    void typedVectorDegradationAndRejectionsStayVisibleWithoutRawDetails() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        doAnswer(invocation -> {
            RetrievalInspectionCollector collector = invocation.getArgument(1);
            collector.channelCandidate(CandidateSignal.LEXICAL, "WIKI:1");
            collector.channelRejected(CandidateSignal.VECTOR, "WIKI:2", "STALE_REVISION");
            collector.selected("WIKI:1");
            return bundleWithDiagnostics("HYBRID_VECTOR", List.of(item("WIKI", "1")),
                    new RetrievalDiagnostics(RetrievalStrategy.HYBRID, true, true, true, false,
                            null));
        }).when(retrievalService).retrieve(any(), any());
        RetrievalInspectorService service = new RetrievalInspectorService(retrievalService,
                policyProvider);

        RetrievalInspectionReport report = service.inspect(
                RetrievalRequest.defaults("q", RetrievalMode.HYBRID_VECTOR));

        assertThat(report.modalities()).extracting(
                        RetrievalInspectionReport.ModalitySection::modality)
                .containsExactly(CandidateSignal.LEXICAL, CandidateSignal.VECTOR);
        assertThat(report.modalities().get(1).rejected()).singleElement().satisfies(rejected -> {
            assertThat(rejected.identity()).isEqualTo("WIKI:2");
            assertThat(rejected.reasonCode()).isEqualTo("STALE_REVISION");
        });
        assertThat(report.modalityDiagnostics().vector()).isEqualTo(ModalityOutcome.DEGRADED);
    }

    @Test
    void unavailableVectorSignalMapsToTypedUnavailableOutcome() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        doAnswer(invocation -> {
            RetrievalInspectionCollector collector = invocation.getArgument(1);
            collector.channelCandidate(CandidateSignal.LEXICAL, "WIKI:1");
            collector.selected("WIKI:1");
            return bundleWithDiagnostics("HYBRID_VECTOR", List.of(item("WIKI", "1")),
                    new RetrievalDiagnostics(RetrievalStrategy.HYBRID, true, false, false, true,
                            "vector unavailable"));
        }).when(retrievalService).retrieve(any(), any());
        RetrievalInspectorService service = new RetrievalInspectorService(retrievalService,
                policyProvider);

        RetrievalInspectionReport report = service.inspect(
                RetrievalRequest.defaults("q", RetrievalMode.HYBRID_VECTOR));

        assertThat(report.modalityDiagnostics().vector()).isEqualTo(ModalityOutcome.UNAVAILABLE);
    }

    @Test
    void reportInvariantRejectsSurvivorCountsThatDoNotMatchFinalEvidence() {
        assertThatIllegalArgumentException().isThrownBy(() -> report(
                List.of(RetrievalInspectionTrace.SelectionTrace.selected("WIKI:1"),
                        RetrievalInspectionTrace.SelectionTrace.rejected("WIKI:2", "INELIGIBLE")),
                List.of(new RetrievalInspectionReport.FinalEvidence(1, "WIKI:1"),
                        new RetrievalInspectionReport.FinalEvidence(2, "WIKI:2"))));
        assertThatIllegalArgumentException().isThrownBy(() -> report(
                List.of(RetrievalInspectionTrace.SelectionTrace.selected("WIKI:1")),
                List.of()));
    }

    @Test
    void reportInvariantAcceptsSelectedThenRejectedSurvivorSemantics() {
        RetrievalInspectionReport inspected = report(
                List.of(RetrievalInspectionTrace.SelectionTrace.selected("WIKI:1"),
                        RetrievalInspectionTrace.SelectionTrace.rejected("WIKI:1",
                                "STALE_REVISION"),
                        RetrievalInspectionTrace.SelectionTrace.selected("WIKI:2")),
                List.of(new RetrievalInspectionReport.FinalEvidence(1, "WIKI:2")));

        assertThat(inspected.finalEvidence()).singleElement().satisfies(evidence ->
                assertThat(evidence.identity()).isEqualTo("WIKI:2"));
    }

    private static RetrievalInspectionReport report(
            List<RetrievalInspectionTrace.SelectionTrace> selection,
            List<RetrievalInspectionReport.FinalEvidence> finalEvidence) {
        return new RetrievalInspectionReport("q", RetrievalMode.HYBRID_GRAPH,
                RetrievalStrategy.FUSED, new EvidenceWorkspace(7L, "ws"),
                "fusion-rrf-v2-graph-damped",
                List.of(new RetrievalInspectionReport.ModalitySection(CandidateSignal.LEXICAL,
                        ModalityOutcome.CONTRIBUTED, List.of(), List.of())),
                List.of("WIKI:1", "WIKI:2"), selection, finalEvidence, Map.of(),
                new FusedModalityDiagnostics(ModalityOutcome.CONTRIBUTED, ModalityOutcome.DISABLED,
                        ModalityOutcome.DISABLED, null, null, 0),
                2, 1, false, new EvidenceBudget(8, 12_000, finalEvidence.size(), 10, 3, false));
    }

    @Test
    void rejectsNullRequest() {
        RetrievalInspectorService service = new RetrievalInspectorService(
                mock(RetrievalService.class), policyProvider);

        assertThatIllegalArgumentException().isThrownBy(() -> service.inspect(null));
    }

    private static EvidenceBundle bundle(String mode, EvidenceItem... items) {
        return bundleWithDiagnostics(mode, List.of(items), RetrievalDiagnostics.lexical());
    }

    private static EvidenceBundle bundleWithDiagnostics(String mode, List<EvidenceItem> items,
                                                        RetrievalDiagnostics diagnostics) {
        return new EvidenceBundle("q", RetrievalMode.valueOf(mode),
                new EvidenceWorkspace(7L, "ws"), items,
                new EvidenceBudget(8, 12_000, items.size(), 10, 3, false),
                3, 1, items.isEmpty(), diagnostics);
    }

    private static EvidenceItem item(String kind, String stableId) {
        return new EvidenceItem(EvidenceKind.valueOf(kind), stableId,
                new EvidenceWorkspace(7L, "ws"), 0.5, "content", "snippet", false, "hash",
                kind.equals("WIKI") ? stableId : null, "title", "PAGE", "vault/p.md", 4,
                kind.equals("SOURCE_CHUNK") ? 7L : null,
                kind.equals("SOURCE_CHUNK") ? 900L : null, "doc.pdf",
                kind.equals("SOURCE_CHUNK") ? 2 : null, null, null, null);
    }
}
