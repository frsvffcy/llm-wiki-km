package org.km.llmwiki.rag;

import org.km.llmwiki.graph.GraphProjectionSnapshot;

import java.util.List;

/**
 * Deterministic outcome of converting one graph traversal result into canonical evidence. Only
 * revalidated {@link EvidenceItem}s are admitted; every other candidate carries a typed rejection.
 */
public record GraphEvidenceAdmissionResult(GraphProjectionSnapshot snapshot,
                                           EvidenceWorkspace workspace,
                                           List<EvidenceItem> evidenceItems,
                                           EvidenceBudget budget,
                                           int candidateCount,
                                           int rejectedCandidateCount,
                                           List<GraphCandidateRejection> rejections) {

    public GraphEvidenceAdmissionResult {
        if (snapshot == null || workspace == null) {
            throw new IllegalArgumentException("Graph evidence admission result is incomplete");
        }
        evidenceItems = List.copyOf(evidenceItems);
        rejections = List.copyOf(rejections);
        if (rejectedCandidateCount != rejections.size()) {
            throw new IllegalArgumentException(
                    "Graph evidence admission rejection count must match rejection diagnostics");
        }
        if (evidenceItems.size() + rejectedCandidateCount > candidateCount) {
            throw new IllegalArgumentException(
                    "Graph evidence admission outcome cannot exceed the traversal candidate count");
        }
    }

    public boolean budgetTruncated() {
        return budget.truncated();
    }
}
