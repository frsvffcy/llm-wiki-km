package org.km.llmwiki.wiki;

import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only application boundary for converting one approved proposal into a Wiki Draft. */
@Service
public class WikiDraftService {

    private final WorkspaceService workspaceService;
    private final KnowledgeProposalRepository proposalRepository;
    private final WikiDraftConverter converter;

    public WikiDraftService(WorkspaceService workspaceService, KnowledgeProposalRepository proposalRepository,
                            WikiDraftConverter converter) {
        this.workspaceService = workspaceService;
        this.proposalRepository = proposalRepository;
        this.converter = converter;
    }

    @Transactional(readOnly = true)
    public WikiDraft convertApproved(long proposalId) {
        if (proposalId <= 0) {
            throw new KnowledgeProposalNotFoundException(proposalId);
        }
        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
        WikiDraftConversionSource source = proposalRepository.findDraftConversionSource(workspace.id(), proposalId)
                .orElseThrow(() -> new KnowledgeProposalNotFoundException(proposalId));
        return converter.convert(source);
    }
}
