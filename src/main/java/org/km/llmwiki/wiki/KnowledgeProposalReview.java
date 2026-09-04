package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;

import java.util.List;

/** 人工審核佇列的讀取模型；不會產生 Wiki 或檔案系統副作用。 */
public record KnowledgeProposalReview(long id, LlmProposalAction action, KnowledgeProposalStatus status,
                                      String targetReference, long documentId, String documentFileName,
                                      String documentSourcePath, long candidateId, String title, String summary,
                                      double confidence, String rationale,
                                      List<KnowledgeProposalEvidence> evidence) {
}
