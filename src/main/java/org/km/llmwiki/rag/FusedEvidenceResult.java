package org.km.llmwiki.rag;

import java.util.List;

/**
 * Deterministic outcome of one three-modality fusion. Items are deduplicated by canonical
 * evidence identity, rank-ordered by application-owned policy, budget-bounded, and already
 * terminal-publication-guarded. The future Graph-grounded Ask surface assembles its own
 * {@code EvidenceBundle} from these items; this boundary deliberately introduces no public
 * retrieval mode.
 */
public record FusedEvidenceResult(String query, EvidenceWorkspace workspace,
                                  List<EvidenceItem> items, EvidenceBudget budget,
                                  int searchedCandidateCount, int rejectedCandidateCount,
                                  boolean insufficientEvidence,
                                  FusedModalityDiagnostics diagnostics) {

    public FusedEvidenceResult {
        if (query == null || query.isBlank() || workspace == null) {
            throw new IllegalArgumentException("Fusion result identity is incomplete");
        }
        items = List.copyOf(items);
        if (insufficientEvidence != items.isEmpty()) {
            throw new IllegalArgumentException(
                    "Fusion insufficient evidence must equal empty items");
        }
        if (budget == null || diagnostics == null) {
            throw new IllegalArgumentException("Fusion result budget and diagnostics are required");
        }
        if (rejectedCandidateCount < 0 || searchedCandidateCount < 0) {
            throw new IllegalArgumentException("Fusion counts must not be negative");
        }
    }
}
