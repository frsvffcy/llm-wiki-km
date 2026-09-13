package org.km.llmwiki.wiki;

/**
 * Result of the governed Ask -> Proposal ingress: the created (or already-existing,
 * deduplicated) proposal plus an explicit duplicate flag.
 */
public record AskProposalIngressResponse(KnowledgeProposalReviewResponse proposal, boolean duplicate) {
}
