package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.KnowledgeCandidateType;
import org.km.llmwiki.ai.LlmProposalAction;

import java.util.List;

/** Internal persistence projection used only to produce a validated Wiki Draft. */
public record WikiDraftConversionSource(long proposalId, long workspaceId, Long documentId,
                                        LlmProposalAction action, KnowledgeProposalStatus status,
                                        String mergeTargetReference, String normalizedDataJson,
                                        KnowledgeCandidateType candidateType, String candidateTitle,
                                        String candidateSummary, List<Long> candidateEvidenceSourceChunkIds,
                                        List<KnowledgeProposalEvidence> proposalEvidence,
                                        List<Long> evidenceDocumentIds) {
    public WikiDraftConversionSource {
        candidateEvidenceSourceChunkIds = List.copyOf(candidateEvidenceSourceChunkIds);
        proposalEvidence = List.copyOf(proposalEvidence);
        evidenceDocumentIds = List.copyOf(evidenceDocumentIds);
    }
}
