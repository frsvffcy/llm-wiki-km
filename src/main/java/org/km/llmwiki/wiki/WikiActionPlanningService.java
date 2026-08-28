package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only application boundary for Proposal + WikiDraft to WikiActionPlan. */
@Service
public class WikiActionPlanningService {

    private final WorkspaceService workspaceService;
    private final KnowledgeProposalRepository proposalRepository;
    private final WikiDraftConverter draftConverter;
    private final WikiActionPlanner actionPlanner;

    public WikiActionPlanningService(WorkspaceService workspaceService,
                                     KnowledgeProposalRepository proposalRepository,
                                     WikiDraftConverter draftConverter,
                                     WikiActionPlanner actionPlanner) {
        this.workspaceService = workspaceService;
        this.proposalRepository = proposalRepository;
        this.draftConverter = draftConverter;
        this.actionPlanner = actionPlanner;
    }

    @Transactional(readOnly = true)
    public WikiActionPlan planApproved(long proposalId) {
        if (proposalId <= 0) {
            throw new KnowledgeProposalNotFoundException(proposalId);
        }
        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
        WikiDraftConversionSource source = proposalRepository.findDraftConversionSource(workspace.id(), proposalId)
                .orElseThrow(() -> new KnowledgeProposalNotFoundException(proposalId));
        if (source.status() != KnowledgeProposalStatus.APPROVED) {
            throw new WikiDraftValidationException(WikiDraftValidationException.Reason.PROPOSAL_NOT_APPROVED,
                    "Only APPROVED knowledge proposals can produce Wiki action plans");
        }
        if (source.action() == LlmProposalAction.CREATE || source.action() == LlmProposalAction.MERGE) {
            return actionPlanner.planWrite(workspace.id(), draftConverter.convert(source));
        }
        return actionPlanner.planNonWrite(source.proposalId(), source.action(), source.proposalEvidence().stream()
                .map(KnowledgeProposalEvidence::sourceChunkId)
                .sorted()
                .toList());
    }
}
