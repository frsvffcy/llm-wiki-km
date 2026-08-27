package org.km.llmwiki.wiki;

import org.km.llmwiki.web.PageResponse;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 驗證目前工作區存取權與 Proposal 審核生命週期決策。 */
@Service
public class KnowledgeProposalReviewService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final WorkspaceService workspaceService;
    private final KnowledgeProposalRepository proposalRepository;

    public KnowledgeProposalReviewService(WorkspaceService workspaceService,
                                          KnowledgeProposalRepository proposalRepository) {
        this.workspaceService = workspaceService;
        this.proposalRepository = proposalRepository;
    }

    public PageResponse<List<KnowledgeProposalReviewResponse>> list(String rawStatus, Integer page, Integer size) {
        KnowledgeProposalStatus status = parseStatus(rawStatus);
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        validatePage(pageNumber, pageSize);
        WorkspaceResponse workspace = activeWorkspace();
        long total = proposalRepository.countReviewable(workspace.id(), status);
        List<KnowledgeProposalReviewResponse> proposals = proposalRepository.findReviewable(workspace.id(), status,
                        (long) pageNumber * pageSize, pageSize)
                .stream().map(KnowledgeProposalReviewResponse::from).toList();
        return PageResponse.of(proposals, pageNumber, pageSize, total);
    }

    public KnowledgeProposalReviewResponse get(long proposalId) {
        return KnowledgeProposalReviewResponse.from(requireVisibleProposal(proposalId));
    }

    @Transactional
    public KnowledgeProposalReviewResponse updateStatus(long proposalId, KnowledgeProposalStatusUpdateRequest request) {
        if (request == null || request.status() == null) {
            throw new IllegalArgumentException("status is required");
        }
        KnowledgeProposalReview current = requireVisibleProposal(proposalId);
        current.status().requireTransitionTo(request.status());
        proposalRepository.transitionStatus(proposalId, current.status(), request.status());
        return get(proposalId);
    }

    private KnowledgeProposalReview requireVisibleProposal(long proposalId) {
        if (proposalId <= 0) {
            throw new KnowledgeProposalNotFoundException(proposalId);
        }
        WorkspaceResponse workspace = activeWorkspace();
        return proposalRepository.findReviewableById(workspace.id(), proposalId)
                .orElseThrow(() -> new KnowledgeProposalNotFoundException(proposalId));
    }

    private WorkspaceResponse activeWorkspace() {
        return workspaceService.findActiveWithoutValidation().orElseThrow(NoActiveWorkspaceException::new);
    }

    private static KnowledgeProposalStatus parseStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return null;
        }
        try {
            return KnowledgeProposalStatus.valueOf(rawStatus);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("status is not supported: " + rawStatus);
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }
}
