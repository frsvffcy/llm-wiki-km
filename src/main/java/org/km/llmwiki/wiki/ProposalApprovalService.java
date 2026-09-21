package org.km.llmwiki.wiki;

import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

/**
 * #570：Proposal → Draft 收斂的操作邊界（task-first governance UX）。
 *
 * <p>流程：狀態轉換先提交（既有 {@link KnowledgeProposalReviewService#updateStatus} 交易語意不變）；
 * 僅當本次轉換目標為 APPROVED 時，於獨立交易自動準備對應草稿（get-or-create、可重試、
 * 失敗不回滾核准）。Publish 永遠保留獨立明確人工作動——本服務不觸碰任何 publish 路徑。
 * REPAIR／ASK 提案走同一條程式路徑，不建立第二套 transition authority。
 */
@Service
public class ProposalApprovalService {

    private final WorkspaceService workspaceService;
    private final KnowledgeProposalReviewService reviewService;
    private final ProposalAutoDraftService autoDraftService;

    public ProposalApprovalService(WorkspaceService workspaceService,
                                   KnowledgeProposalReviewService reviewService,
                                   ProposalAutoDraftService autoDraftService) {
        this.workspaceService = workspaceService;
        this.reviewService = reviewService;
        this.autoDraftService = autoDraftService;
    }

    /**
     * 執行狀態轉換；APPROVE 成功後附帶自動草稿結果（成功／沿用／typed 失敗）。
     * 非 APPROVE 轉換回傳內容與既有 contract 一致（autoDraft 為 null）。
     */
    public KnowledgeProposalReviewResponse transition(long proposalId,
                                                     KnowledgeProposalStatusUpdateRequest request) {
        KnowledgeProposalReviewResponse review = reviewService.updateStatus(proposalId, request);
        if (request == null || request.status() != KnowledgeProposalStatus.APPROVED) {
            return review;
        }
        long workspaceId = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new).id();
        return review.withAutoDraft(autoDraftService.prepare(workspaceId, review.id()));
    }
}
