package org.km.llmwiki.rag;

import org.km.llmwiki.ai.query.QueryTransformationResult;
import org.km.llmwiki.ai.query.QueryTransformationService;
import org.km.llmwiki.ai.query.QueryTransformationStatus;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final QueryTransformationService queryTransformationService;

    public RetrievalInspectorService(RetrievalService retrievalService,
                                     FusionRankingPolicyProvider fusionPolicyProvider) {
        this(retrievalService, fusionPolicyProvider, QueryTransformationService.disabled());
    }

    @Autowired
    public RetrievalInspectorService(RetrievalService retrievalService,
                                     FusionRankingPolicyProvider fusionPolicyProvider,
                                     QueryTransformationService queryTransformationService) {
        this.retrievalService = retrievalService;
        this.fusionPolicyProvider = fusionPolicyProvider;
        this.queryTransformationService = queryTransformationService;
    }

    public RetrievalInspectionReport inspect(RetrievalRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("retrieval request must not be null");
        }
        RetrievalInspectionCollector originalCollector = new RetrievalInspectionCollector();
        EvidenceBundle originalBundle = retrievalService.retrieve(request, originalCollector);
        RetrievalInspectionTrace originalTrace = originalCollector.toTrace();
        List<RetrievalInspectionReport.InputObservation> inputs = new ArrayList<>();
        inputs.add(inputObservation(1, RetrievalInspectionReport.InputRole.ORIGINAL,
                request, originalBundle, originalTrace));

        QueryTransformationResult transformed = queryTransformationService.apply(
                request, originalBundle, rewriteRequest -> {
                    RetrievalInspectionCollector rewriteCollector =
                            new RetrievalInspectionCollector();
                    try {
                        EvidenceBundle rewriteBundle = retrievalService.retrieve(
                                rewriteRequest, rewriteCollector);
                        inputs.add(inputObservation(2,
                                RetrievalInspectionReport.InputRole.REWRITE,
                                rewriteRequest, rewriteBundle, rewriteCollector.toTrace()));
                        return rewriteBundle;
                    } catch (RetrievalUnavailableException failure) {
                        inputs.add(failedInputObservation(2,
                                RetrievalInspectionReport.InputRole.REWRITE,
                                rewriteRequest, rewriteCollector.toTrace()));
                        throw failure;
                    }
                });
        EvidenceBundle bundle = transformed.evidence();
        boolean rewriteApplied = transformed.execution().status()
                == QueryTransformationStatus.REWRITE_APPLIED;
        List<RetrievalInspectionTrace.SelectionTrace> finalSelection = rewriteApplied
                ? bundle.items().stream().map(item ->
                        RetrievalInspectionTrace.SelectionTrace.selected(item.stableIdentity()))
                        .toList()
                : originalTrace.selection();
        return new RetrievalInspectionReport(
                bundle.query(),
                bundle.mode(),
                request.strategy(),
                bundle.workspace(),
                request.strategy() == RetrievalStrategy.FUSED
                        ? fusionPolicyProvider.policy().version() : null,
                modalitySections(originalBundle, originalTrace),
                originalTrace.fusedOrder(),
                finalSelection,
                finalEvidence(bundle),
                originalTrace.itemModalities(),
                modalityDiagnostics(originalBundle, originalTrace),
                bundle.searchedCandidateCount(),
                bundle.rejectedCandidateCount(),
                bundle.insufficientEvidence(),
                bundle.budget(),
                transformed.execution(),
                inputs);
    }

    private RetrievalInspectionReport.InputObservation inputObservation(
            int ordinal, RetrievalInspectionReport.InputRole role, RetrievalRequest request,
            EvidenceBundle bundle, RetrievalInspectionTrace trace) {
        return new RetrievalInspectionReport.InputObservation(ordinal, role, request.query(),
                modalitySections(bundle, trace), trace.fusedOrder(), trace.selection());
    }

    private static RetrievalInspectionReport.InputObservation failedInputObservation(
            int ordinal, RetrievalInspectionReport.InputRole role, RetrievalRequest request,
            RetrievalInspectionTrace trace) {
        List<RetrievalInspectionReport.ModalitySection> sections = trace.modalities().stream()
                .map(modality -> new RetrievalInspectionReport.ModalitySection(
                        modality.modality(), ModalityOutcome.UNAVAILABLE,
                        modality.candidates(), modality.rejected()))
                .toList();
        return new RetrievalInspectionReport.InputObservation(ordinal, role, request.query(),
                sections, trace.fusedOrder(), trace.selection());
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
