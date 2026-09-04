package org.km.llmwiki.rag;

import java.util.List;

/**
 * Authoritative retrieval result consumed by Answer context assembly and Ask orchestration; it
 * never contains a generated answer.
 */
public record EvidenceBundle(
        String query,
        RetrievalMode mode,
        EvidenceWorkspace workspace,
        List<EvidenceItem> items,
        EvidenceBudget budget,
        int searchedCandidateCount,
        int rejectedCandidateCount,
        boolean insufficientEvidence,
        RetrievalDiagnostics diagnostics
) {
    public EvidenceBundle(String query, RetrievalMode mode, EvidenceWorkspace workspace,
                          List<EvidenceItem> items, EvidenceBudget budget,
                          int searchedCandidateCount, int rejectedCandidateCount,
                          boolean insufficientEvidence) {
        this(query, mode, workspace, items, budget, searchedCandidateCount,
                rejectedCandidateCount, insufficientEvidence,
                mode == null ? RetrievalDiagnostics.lexical()
                        : switch (mode.strategy()) {
                            case LEXICAL -> RetrievalDiagnostics.lexical();
                            case SEMANTIC -> RetrievalDiagnostics.semantic();
                            case HYBRID -> RetrievalDiagnostics.hybrid();
                        });
    }

    public EvidenceBundle {
        items = List.copyOf(items);
        if (diagnostics == null) {
            throw new IllegalArgumentException("retrieval diagnostics are required");
        }
        if (insufficientEvidence != items.isEmpty()) {
            throw new IllegalArgumentException(
                    "insufficientEvidence must reflect whether usable evidence is empty");
        }
    }
}
