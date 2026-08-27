package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;

import java.util.List;

/** Persisted review workflow data; this is not a Wiki Page or a publish command. */
public record KnowledgeProposal(long id, long workspaceId, long documentAnalysisId, long documentId,
                                long knowledgeCandidateId, LlmProposalAction action,
                                KnowledgeProposalStatus status, String mergeTargetReference,
                                String provider, String model, String promptIdentifier, String promptVersion,
                                String contractVersion, String validatedPayloadJson, String normalizedDataJson,
                                List<Long> evidenceSourceChunkIds) {
}
