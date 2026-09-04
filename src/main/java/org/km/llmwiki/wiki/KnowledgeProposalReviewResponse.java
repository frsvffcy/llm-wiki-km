package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;

import java.util.List;

/** 僅含 Proposal 審核所需資訊的 API 表示。 */
public record KnowledgeProposalReviewResponse(long id, LlmProposalAction action, KnowledgeProposalStatus status,
                                              String title, String summary, String rationale, double confidence,
                                              String targetReference, SourceDocument sourceDocument,
                                              List<KnowledgeProposalEvidence> evidence) {

    public static KnowledgeProposalReviewResponse from(KnowledgeProposalReview proposal) {
        return new KnowledgeProposalReviewResponse(proposal.id(), proposal.action(), proposal.status(), proposal.title(),
                proposal.summary(), proposal.rationale(), proposal.confidence(), proposal.targetReference(),
                new SourceDocument(proposal.documentId(), proposal.documentFileName(), proposal.documentSourcePath()),
                proposal.evidence());
    }

    public record SourceDocument(long id, String fileName, String sourcePath) {
    }
}
