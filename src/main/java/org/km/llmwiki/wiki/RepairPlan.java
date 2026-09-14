package org.km.llmwiki.wiki;

import java.util.List;

/**
 * Deterministic repair plan derived entirely from backend authorities (#384): the
 * finding's target page row, its resolvable governed origin proposal, and the origin's
 * normalized data. The plan carries no client-supplied detail, path, or text — the
 * repair command only names the canonical identity and the server rebuilds the rest.
 */
public record RepairPlan(String kind, String kindVersion, String knowledgeId, String findingCode,
                         String baseContentHash, long originProposalId, String normalizedDataJson,
                         String mergeTargetReference, List<Long> evidenceChunkIds,
                         String validatedPayloadJson, String dedupHash) {
    public RepairPlan {
        evidenceChunkIds = List.copyOf(evidenceChunkIds);
    }
}
