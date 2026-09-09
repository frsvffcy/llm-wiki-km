package org.km.llmwiki.rag;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only Retrieval Inspector boundary. It reruns nothing: the same production retrieval
 * path serves the request while an optional collector observes, so the reported final evidence
 * is by construction the production handoff result for the same input and current state. The
 * inspector never calls an answer provider and never mutates canonical state.
 */
@Service
public class RetrievalInspectorService {

    private final RetrievalService retrievalService;
    private final FusionRankingPolicyProvider fusionPolicyProvider;

    public RetrievalInspectorService(RetrievalService retrievalService,
                                     FusionRankingPolicyProvider fusionPolicyProvider) {
        this.retrievalService = retrievalService;
        this.fusionPolicyProvider = fusionPolicyProvider;
    }

    public RetrievalInspectionReport inspect(RetrievalRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("retrieval request must not be null");
        }
        RetrievalInspectionCollector channelCollector = new RetrievalInspectionCollector();
        EvidenceBundle bundle = retrievalService.retrieve(request, channelCollector);
        RetrievalInspectionTrace trace = channelCollector.toTrace();
        return new RetrievalInspectionReport(
                bundle.query(),
                bundle.mode(),
                request.strategy(),
                bundle.workspace(),
                request.strategy() == RetrievalStrategy.FUSED
                        ? fusionPolicyProvider.policy().version() : null,
                modalitySections(bundle, trace),
                trace.fusedOrder(),
                trace.selection(),
                finalEvidence(bundle),
                trace.itemModalities(),
                modalityDiagnostics(bundle, trace),
                bundle.searchedCandidateCount(),
                bundle.rejectedCandidateCount(),
                bundle.insufficientEvidence(),
                bundle.budget());
    }

    private List<RetrievalInspectionReport.ModalitySection> modalitySections(
            EvidenceBundle bundle, RetrievalInspectionTrace trace) {
        List<RetrievalInspectionReport.ModalitySection> sections = new ArrayList<>();
        for (RetrievalInspectionTrace.ModalityTrace modality : trace.modalities()) {
            sections.add(new RetrievalInspectionReport.ModalitySection(
                    modality.modality(),
                    sectionOutcome(bundle, modality.modality(), modality.candidates()),
                    modality.candidates(),
                    modality.rejected()));
        }
        return sections;
    }

    private static ModalityOutcome sectionOutcome(EvidenceBundle bundle,
                                                  CandidateSignal modality,
                                                  List<RetrievalInspectionTrace.CandidateTrace>
                                                          candidates) {
        if (!candidates.isEmpty()) {
            return ModalityOutcome.CONTRIBUTED;
        }
        RetrievalDiagnostics diagnostics = bundle.diagnostics();
        return switch (modality) {
            case LEXICAL -> diagnostics.lexicalSignalUsed()
                    ? ModalityOutcome.EMPTY : ModalityOutcome.DISABLED;
            case VECTOR -> signalOutcome(diagnostics.vectorSignalUsed(),
                    diagnostics.vectorUnavailable(), diagnostics.degradedFallback());
            case GRAPH -> signalOutcome(diagnostics.graphSignalUsed(),
                    diagnostics.graphUnavailable(), diagnostics.graphDegraded());
        };
    }

    private static ModalityOutcome signalOutcome(boolean signalUsed, boolean unavailable,
                                                 boolean degraded) {
        if (unavailable) {
            return ModalityOutcome.UNAVAILABLE;
        }
        if (degraded) {
            return ModalityOutcome.DEGRADED;
        }
        return signalUsed ? ModalityOutcome.EMPTY : ModalityOutcome.DISABLED;
    }

    private static List<RetrievalInspectionReport.FinalEvidence> finalEvidence(
            EvidenceBundle bundle) {
        List<RetrievalInspectionReport.FinalEvidence> evidence = new ArrayList<>();
        for (EvidenceItem item : bundle.items()) {
            evidence.add(new RetrievalInspectionReport.FinalEvidence(
                    evidence.size() + 1, item.stableIdentity()));
        }
        return List.copyOf(evidence);
    }

    /** One unified typed modality view derived from the production diagnostics. */
    private static FusedModalityDiagnostics modalityDiagnostics(EvidenceBundle bundle,
                                                                RetrievalInspectionTrace trace) {
        RetrievalDiagnostics diagnostics = bundle.diagnostics();
        return new FusedModalityDiagnostics(
                diagnostics.lexicalSignalUsed()
                        ? candidateStatus(trace, CandidateSignal.LEXICAL)
                        : ModalityOutcome.DISABLED,
                degradedVectorOutcome(diagnostics, trace),
                degradedGraphOutcome(diagnostics, trace),
                null, null, bundle.rejectedCandidateCount());
    }

    private static ModalityOutcome degradedVectorOutcome(RetrievalDiagnostics diagnostics,
                                                         RetrievalInspectionTrace trace) {
        if (diagnostics.vectorUnavailable()) {
            return ModalityOutcome.UNAVAILABLE;
        }
        if (diagnostics.degradedFallback()) {
            return ModalityOutcome.DEGRADED;
        }
        return diagnostics.vectorSignalUsed()
                ? candidateStatus(trace, CandidateSignal.VECTOR) : ModalityOutcome.DISABLED;
    }

    private static ModalityOutcome degradedGraphOutcome(RetrievalDiagnostics diagnostics,
                                                        RetrievalInspectionTrace trace) {
        if (diagnostics.graphUnavailable()) {
            return ModalityOutcome.UNAVAILABLE;
        }
        if (diagnostics.graphDegraded()) {
            return ModalityOutcome.DEGRADED;
        }
        return diagnostics.graphSignalUsed()
                ? candidateStatus(trace, CandidateSignal.GRAPH) : ModalityOutcome.DISABLED;
    }

    private static ModalityOutcome candidateStatus(RetrievalInspectionTrace trace,
                                                   CandidateSignal modality) {
        return trace.modalities().stream()
                .filter(section -> section.modality() == modality)
                .findFirst()
                .map(section -> section.candidates().isEmpty()
                        ? ModalityOutcome.EMPTY : ModalityOutcome.CONTRIBUTED)
                .orElse(ModalityOutcome.EMPTY);
    }
}
