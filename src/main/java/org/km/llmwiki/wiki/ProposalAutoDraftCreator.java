package org.km.llmwiki.wiki;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * #570：核准後自動建草稿的寫入嘗試（獨立交易）。
 *
 * <p>REQUIRES_NEW：呼叫時核准轉換已提交，可靠讀見 APPROVED；成功即提交。
 * 失敗在交易邊界<b>內</b>轉為 typed {@link ProposalAutoDraft#failed} 並明確標記
 * rollback-only（失敗嘗試不留半成品 draft rows），例外絕不穿越 proxy 邊界——
 * 避免把外層交易標成 rollback-only 後才被呼叫端 catch（Spring
 *「例外穿過 @Transactional 邊界即標記回滾」語意）。
 */
@Service
public class ProposalAutoDraftCreator {

    private final WikiDraftPersistenceService draftPersistenceService;

    public ProposalAutoDraftCreator(WikiDraftPersistenceService draftPersistenceService) {
        this.draftPersistenceService = draftPersistenceService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProposalAutoDraft create(long proposalId) {
        try {
            WikiDraftResponse created = draftPersistenceService.create(new CreateWikiDraftRequest(proposalId));
            return ProposalAutoDraft.created(created.id(), created.status());
        } catch (WikiDraftValidationException exception) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return ProposalAutoDraft.failed("AUTO_DRAFT_" + exception.reason().name(),
                    autoDraftMessage(exception.reason()));
        } catch (RuntimeException exception) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return ProposalAutoDraft.failed("AUTO_DRAFT_FAILED", "草稿自動準備失敗，請重試或手動建立草稿");
        }
    }

    private static String autoDraftMessage(WikiDraftValidationException.Reason reason) {
        return switch (reason) {
            case PROPOSAL_NOT_APPROVED -> "提案狀態尚未就緒，請重新整理後再試";
            case UNSUPPORTED_ACTION -> "此提案類型不會產生草稿，可直接關閉審核";
            case AMBIGUOUS_CANDIDATE_MAPPING, UNSUPPORTED_CANDIDATE_MAPPING ->
                    "系統無法自動決定草稿放置位置，請手動建立草稿並選擇類型";
            case INVALID_NORMALIZED_DATA, INVALID_EVIDENCE ->
                    "提案資料驗證失敗，請手動建立草稿並檢查提案內容";
            case UNSAFE_TARGET_REFERENCE, PATH_CONTRACT_MISMATCH ->
                    "草稿目標路徑驗證失敗，請手動建立草稿並檢查目標";
        };
    }
}
