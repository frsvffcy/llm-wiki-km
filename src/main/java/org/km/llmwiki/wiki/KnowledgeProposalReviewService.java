package org.km.llmwiki.wiki;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final ObjectMapper objectMapper;

    public KnowledgeProposalReviewService(WorkspaceService workspaceService,
                                          KnowledgeProposalRepository proposalRepository,
                                          ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.proposalRepository = proposalRepository;
        this.objectMapper = objectMapper;
    }

    public PageResponse<List<KnowledgeProposalReviewResponse>> list(String rawStatus, Integer page, Integer size) {
        return list(rawStatus, page, size, null);
    }

    /** #569：documentId 為 null 時維持原語意；指定時只列該文件的提案（organize surface 用）。 */
    public PageResponse<List<KnowledgeProposalReviewResponse>> list(String rawStatus, Integer page, Integer size,
                                                                     Long documentId) {
        KnowledgeProposalStatus status = parseStatus(rawStatus);
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        validatePage(pageNumber, pageSize);
        if (documentId != null && documentId <= 0) {
            throw new IllegalArgumentException("documentId must be positive");
        }
        WorkspaceResponse workspace = activeWorkspace();
        long total = proposalRepository.countReviewable(workspace.id(), status, documentId);
        List<KnowledgeProposalReviewResponse> proposals = proposalRepository.findReviewable(workspace.id(), status,
                        (long) pageNumber * pageSize, pageSize, documentId)
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

    /**
     * #569：人類在 REVIEW 階段調整 proposal tags（唯一的人控 tag mutation point）。
     *
     * <p>語意：workspace-scoped；只有 REVIEW 且非 REPAIR lineage 可編輯；tags 經
     * {@link KnowledgeTagPolicy} 正規化後寫回 normalized data（其餘 keys 原樣保留）；
     * mutation 當下狀態已漂移（SQL 條件式寫入未命中）則失敗、不假裝成功。
     * 已存在的 draft snapshot 不受影響（immutable；以 regenerate 承接新 tags）。
     */
    @Transactional
    public KnowledgeProposalReviewResponse updateTags(long proposalId, UpdateProposalTagsRequest request) {
        if (proposalId <= 0) {
            throw new KnowledgeProposalNotFoundException(proposalId);
        }
        if (request == null) {
            throw new IllegalArgumentException("tags request is required");
        }
        WorkspaceResponse workspace = activeWorkspace();
        KnowledgeProposalRepository.ProposalTagTarget target = proposalRepository
                .findTagTarget(workspace.id(), proposalId)
                .orElseThrow(() -> new KnowledgeProposalNotFoundException(proposalId));
        if (!KnowledgeOrganizationPolicy.isTagEditableStatus(target.status())) {
            throw new IllegalArgumentException(
                    "Only REVIEW proposals accept tag updates; current status is " + target.status());
        }
        if (!KnowledgeOrganizationPolicy.isTagEditableSourceKind(target.sourceKind())) {
            throw new ProposalTagsNotEditableException(
                    "Proposals of source kind " + target.sourceKind() + " carry deterministic lineage tags");
        }
        List<String> normalized = KnowledgeTagPolicy.normalizedTags(request.tags());
        boolean updated = proposalRepository.updateNormalizedTags(workspace.id(), proposalId,
                replaceTags(target.normalizedDataJson(), normalized));
        if (!updated) {
            throw new IllegalStateException("Knowledge proposal changed status while updating tags");
        }
        return get(proposalId);
    }

    private String replaceTags(String normalizedDataJson, List<String> tags) {
        try {
            ObjectNode data = (ObjectNode) objectMapper.readTree(normalizedDataJson);
            data.set("tags", objectMapper.valueToTree(tags));
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException | ClassCastException exception) {
            throw new IllegalStateException("Persisted proposal normalized data is not a JSON object", exception);
        }
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
