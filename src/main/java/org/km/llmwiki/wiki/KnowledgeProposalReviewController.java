package org.km.llmwiki.wiki;

import org.km.llmwiki.web.ApiResponse;
import org.km.llmwiki.web.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 僅供人工審核 Proposal 的 REST endpoint；發佈不在本功能範圍。 */
@RestController
@RequestMapping("/api/v1/proposals")
public class KnowledgeProposalReviewController {

    private final KnowledgeProposalReviewService reviewService;
    private final ProposalApprovalService approvalService;

    public KnowledgeProposalReviewController(KnowledgeProposalReviewService reviewService,
                                             ProposalApprovalService approvalService) {
        this.reviewService = reviewService;
        this.approvalService = approvalService;
    }

    @GetMapping
    public PageResponse<List<KnowledgeProposalReviewResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Long documentId) {
        return reviewService.list(status, page, size, documentId);
    }

    @GetMapping("/{proposalId}")
    public ApiResponse<KnowledgeProposalReviewResponse> get(@PathVariable long proposalId) {
        return new ApiResponse<>(reviewService.get(proposalId));
    }

    /**
     * #570：狀態轉換經 ProposalApprovalService 收斂——APPROVE 成功後自動準備對應草稿
     * （回應攜帶 {@code autoDraft}；失敗時 proposal 仍為 APPROVED＋typed recovery，
     * 絕不自動 publish）。其餘轉換語意不變。
     */
    @PatchMapping("/{proposalId}/status")
    public ApiResponse<KnowledgeProposalReviewResponse> updateStatus(
            @PathVariable long proposalId, @RequestBody KnowledgeProposalStatusUpdateRequest request) {
        return new ApiResponse<>(approvalService.transition(proposalId, request));
    }

    /**
     * #569：人類在 REVIEW 階段調整 proposal tags（唯一的人控 tag mutation point；
     * REPAIR lineage 回 422，APPROVED／REJECTED 等 terminal 狀態回 400）。
     */
    @PatchMapping("/{proposalId}/tags")
    public ApiResponse<KnowledgeProposalReviewResponse> updateTags(
            @PathVariable long proposalId, @RequestBody UpdateProposalTagsRequest request) {
        return new ApiResponse<>(reviewService.updateTags(proposalId, request));
    }
}
