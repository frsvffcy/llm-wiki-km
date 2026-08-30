package org.km.llmwiki.rag;

import java.util.List;

/** Business result consumed by a future Ask layer; it never contains a generated answer. */
public record EvidenceBundle(
        String query,
        RetrievalMode mode,
        EvidenceWorkspace workspace,
        List<EvidenceItem> items,
        EvidenceBudget budget,
        int searchedCandidateCount,
        int rejectedCandidateCount,
        boolean insufficientEvidence
) {
    public EvidenceBundle {
        items = List.copyOf(items);
        if (insufficientEvidence != items.isEmpty()) {
            throw new IllegalArgumentException(
                    "insufficientEvidence must reflect whether usable evidence is empty");
        }
    }
}
