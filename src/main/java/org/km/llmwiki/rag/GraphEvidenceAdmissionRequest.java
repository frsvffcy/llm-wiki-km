package org.km.llmwiki.rag;

import org.km.llmwiki.graph.GraphProjectionSnapshot;
import org.km.llmwiki.graph.GraphTraversalResult;

/** Provider-neutral admission request: one traversal result plus its requesting workspace. */
public record GraphEvidenceAdmissionRequest(EvidenceWorkspace workspace,
                                            GraphTraversalResult traversal,
                                            GraphEvidenceAdmissionBudget budget) {

    public GraphEvidenceAdmissionRequest {
        if (workspace == null || traversal == null) {
            throw new IllegalArgumentException("Graph evidence admission request is incomplete");
        }
        budget = budget == null ? GraphEvidenceAdmissionBudget.defaults() : budget;
    }

    public static GraphEvidenceAdmissionRequest of(EvidenceWorkspace workspace,
                                                   GraphTraversalResult traversal) {
        return new GraphEvidenceAdmissionRequest(workspace, traversal, null);
    }

    public GraphProjectionSnapshot expectedSnapshot() {
        return traversal.snapshot();
    }
}
