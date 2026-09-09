package org.km.llmwiki.rag;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Safe application-owned projection of one production retrieval execution. The report reuses
 * the canonical evidence identity vocabulary and typed diagnostics; raw backend scores, vendor
 * identifiers, exception details, snapshot tokens, fingerprints, and paths never enter this
 * boundary. The final evidence order is the production handoff order.
 */
public record RetrievalInspectionReport(
        String query,
        RetrievalMode mode,
        RetrievalStrategy strategy,
        EvidenceWorkspace workspace,
        String fusionPolicyVersion,
        List<ModalitySection> modalities,
        List<String> fusedOrder,
        List<RetrievalInspectionTrace.SelectionTrace> selection,
        List<FinalEvidence> finalEvidence,
        Map<String, Set<CandidateSignal>> itemModalities,
        FusedModalityDiagnostics modalityDiagnostics,
        int searchedCandidateCount,
        int rejectedCandidateCount,
        boolean insufficientEvidence,
        EvidenceBudget budget) {

    public RetrievalInspectionReport {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (mode == null || strategy == null) {
            throw new IllegalArgumentException("mode and strategy are required");
        }
        modalities = List.copyOf(modalities);
        fusedOrder = List.copyOf(fusedOrder);
        selection = List.copyOf(selection);
        finalEvidence = List.copyOf(finalEvidence);
        itemModalities = Map.copyOf(itemModalities);
        if (modalityDiagnostics == null) {
            throw new IllegalArgumentException("modality diagnostics are required");
        }
        if (budget == null) {
            throw new IllegalArgumentException("budget is required");
        }
        // A candidate selected at fusion time can still be dropped by the terminal or handoff
        // currentness guards; the surviving count must match the identities whose LAST recorded
        // disposition is still SELECTED.
        java.util.Map<String, RetrievalInspectionTrace.Disposition> lastDispositions =
                new java.util.LinkedHashMap<>();
        for (RetrievalInspectionTrace.SelectionTrace trace : selection) {
            lastDispositions.put(trace.identity(), trace.disposition());
        }
        long surviving = lastDispositions.values().stream()
                .filter(disposition -> disposition
                        == RetrievalInspectionTrace.Disposition.SELECTED).count();
        if (finalEvidence.size() != surviving) {
            throw new IllegalArgumentException("final evidence must match surviving selections");
        }
    }

    public record ModalitySection(CandidateSignal modality, ModalityOutcome outcome,
                                  List<RetrievalInspectionTrace.CandidateTrace> candidates,
                                  List<RetrievalInspectionTrace.RejectedTrace> rejected) {
        public ModalitySection {
            if (modality == null || outcome == null) {
                throw new IllegalArgumentException("modality and outcome are required");
            }
            candidates = List.copyOf(candidates);
            rejected = List.copyOf(rejected);
        }
    }

    /** Final evidence in production handoff order; ordinal is 1-based and application-owned. */
    public record FinalEvidence(int ordinal, String identity) {
        public FinalEvidence {
            if (ordinal < 1) {
                throw new IllegalArgumentException("final evidence ordinal must be positive");
            }
        }
    }

}
