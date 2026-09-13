package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;

import java.util.List;

/**
 * 僅含 Proposal 審核所需資訊的 API 表示。
 *
 * <p>{@code allowedTransitions} 是 additive capability projection（#370）：由
 * {@link KnowledgeProposalStatus#allowedTransitions()} 這個單一 domain transition
 * authority 直接推導，僅描述「目前可嘗試的合法轉換」——不是授權 token；mutation 當下
 * backend 仍透過 {@link KnowledgeProposalStatus#requireTransitionTo} 重新驗證 current state。
 */
public record KnowledgeProposalReviewResponse(long id, LlmProposalAction action, KnowledgeProposalStatus status,
                                              String title, String summary, String rationale, double confidence,
                                              String targetReference, SourceDocument sourceDocument,
                                              List<KnowledgeProposalEvidence> evidence,
                                              List<KnowledgeProposalStatus> allowedTransitions) {

    public static KnowledgeProposalReviewResponse from(KnowledgeProposalReview proposal) {
        return new KnowledgeProposalReviewResponse(proposal.id(), proposal.action(), proposal.status(), proposal.title(),
                proposal.summary(), proposal.rationale(), proposal.confidence(), proposal.targetReference(),
                new SourceDocument(proposal.documentId(), proposal.documentFileName(), proposal.documentSourcePath()),
                proposal.evidence(), proposal.status().allowedTransitions());
    }

    public record SourceDocument(long id, String fileName, String sourcePath) {
    }
}
