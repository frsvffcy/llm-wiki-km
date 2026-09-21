package org.km.llmwiki.wiki;

/**
 * #570：核准後自動準備草稿的 typed 結果投影（additive；失敗不是授權 token）。
 *
 * <p>成功時 {@code draftId／draftStatus} 有值（{@code reused=true} 表示沿用已存在的可用草稿，
 * 未新建）；失敗時 proposal 仍維持 APPROVED，{@code errorCode／errorMessage} 給出
 * operator-safe 的 typed recovery 指引（不含 stack／path／SQL），絕不 fake-publish。
 */
public record ProposalAutoDraft(Long draftId, String draftStatus, Boolean reused, String errorCode,
                                String errorMessage) {

    public static ProposalAutoDraft created(long draftId, WikiDraftStatus status) {
        return new ProposalAutoDraft(draftId, status.name(), false, null, null);
    }

    public static ProposalAutoDraft reused(long draftId, WikiDraftStatus status) {
        return new ProposalAutoDraft(draftId, status.name(), true, null, null);
    }

    public static ProposalAutoDraft failed(String errorCode, String errorMessage) {
        return new ProposalAutoDraft(null, null, false, errorCode, errorMessage);
    }
}
