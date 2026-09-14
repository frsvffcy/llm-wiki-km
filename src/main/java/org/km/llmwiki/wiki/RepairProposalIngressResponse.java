package org.km.llmwiki.wiki;

/** Governed repair ingress outcome: the proposal enters at REVIEW, never auto-published. */
public record RepairProposalIngressResponse(KnowledgeProposalReviewResponse proposal, boolean duplicate) {
}
