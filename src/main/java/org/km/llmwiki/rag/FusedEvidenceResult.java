package org.km.llmwiki.rag;

import org.km.llmwiki.graph.GraphProjectionSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic outcome of one three-modality fusion. Items are deduplicated by canonical
 * evidence identity, rank-ordered by application-owned policy, budget-bounded, and already
 * terminal-publication-guarded. The Graph-grounded Ask surface (the fused retrieval
 * orchestration) assembles its own {@code EvidenceBundle} from these items; this boundary
 * deliberately introduces no public retrieval mode.
 *
 * <p>Modality provenance and the graph projection snapshot travel with the result so the
 * Ask-facing handoff guard can re-check graph-only evidence against the exact projection
 * the graph channel admitted under. They are diagnostics only: they never become citation
 * identity, authority, or ranking input.
 */
public record FusedEvidenceResult(String query, EvidenceWorkspace workspace,
                                  List<EvidenceItem> items, EvidenceBudget budget,
                                  int searchedCandidateCount, int rejectedCandidateCount,
                                  boolean insufficientEvidence,
                                  GraphProjectionSnapshot graphSnapshot,
                                  Map<String, Set<CandidateSignal>> itemModalities,
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
        if (itemModalities == null) {
            throw new IllegalArgumentException("Fusion item modality provenance is required");
        }
        itemModalities = itemModalities.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> {
                            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                                throw new IllegalArgumentException(
                                        "Fusion item modality provenance must not be empty");
                            }
                            return Set.copyOf(entry.getValue());
                        }));
        Set<String> identities = items.stream().map(EvidenceItem::stableIdentity)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!identities.equals(itemModalities.keySet())) {
            throw new IllegalArgumentException(
                    "Fusion item modality provenance must cover exactly the fused items");
        }
    }
}
