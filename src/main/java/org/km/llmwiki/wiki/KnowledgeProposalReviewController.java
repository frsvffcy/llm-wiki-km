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

    public KnowledgeProposalReviewController(KnowledgeProposalReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public PageResponse<List<KnowledgeProposalReviewResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return reviewService.list(status, page, size);
    }

    @GetMapping("/{proposalId}")
    public ApiResponse<KnowledgeProposalReviewResponse> get(@PathVariable long proposalId) {
        return new ApiResponse<>(reviewService.get(proposalId));
    }

    @PatchMapping("/{proposalId}/status")
    public ApiResponse<KnowledgeProposalReviewResponse> updateStatus(
            @PathVariable long proposalId, @RequestBody KnowledgeProposalStatusUpdateRequest request) {
        return new ApiResponse<>(reviewService.updateStatus(proposalId, request));
    }
}
