package org.km.llmwiki.wiki;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    private final WikiDraftCreationService draftCreationService;

    public ProposalAutoDraftCreator(WikiDraftCreationService draftCreationService) {
        this.draftCreationService = draftCreationService;
    }

    private static final Logger log = LoggerFactory.getLogger(ProposalAutoDraftCreator.class);

    /**
     * 非交易協調器（#601）：实际交易邊界在 {@link WikiDraftCreationService} 內，
     * 建立鎖總是在第一條 SQL 之前取得——若在此先開交易，等待鎖期間 snapshot 即 stale
     * （SQLITE_BUSY_SNAPSHOT 不受 busy_timeout 覆蓋）。呼叫前核准轉換已提交，
     * 因此 REQUIRED 與 REQUIRES_NEW 在此等價。
     */
    public ProposalAutoDraft create(long proposalId) {
        try {
            // #601：與 manual create 共用 atomic create-or-reuse（storage index 為唯一真相）。
            WikiDraftCreationService.CreatedDraft result = draftCreationService.createOrReuse(proposalId);
            WikiDraftResponse response = result.response();
            return result.reused()
                    ? ProposalAutoDraft.reused(response.id(), response.status())
                    : ProposalAutoDraft.created(response.id(), response.status());
        } catch (WikiDraftValidationException exception) {
            // 回應維持 operator-safe；完整 root cause 只進 server-side log（redaction 邊界）。
            // 內層 persistence 交易已由其代理回滾，此處無需標記。
            log.warn("Auto-draft preparation failed for proposal {}", proposalId, exception);
            return ProposalAutoDraft.failed("AUTO_DRAFT_" + exception.reason().name(),
                    autoDraftMessage(exception.reason()));
        } catch (RuntimeException exception) {
            log.warn("Auto-draft preparation failed for proposal {}", proposalId, exception);
            return ProposalAutoDraft.failed("AUTO_DRAFT_FAILED", "草稿自動準備失敗，請重試或手動建立草稿");
        }
    }

    private static String autoDraftMessage(WikiDraftValidationException.Reason reason) {
        return switch (reason) {
            case PROPOSAL_NOT_APPROVED -> "提案狀態尚未就緒，請重新整理後再試";
            case UNSUPPORTED_ACTION -> "此提案類型不會產生草稿，可直接關閉審核";
            case AMBIGUOUS_CANDIDATE_MAPPING, UNSUPPORTED_CANDIDATE_MAPPING ->
                    "系統無法自動決定草稿放置位置，請手動建立草稿並選擇類型";
            case INVALID_NORMALIZED_DATA ->
                    "提案資料驗證失敗，請回到提案來源重新產生或修正後再試";
            case INVALID_EVIDENCE ->
                    "提案引用來源已失效或不一致，請重新取得最新來源並建立新的提案";
            case UNSAFE_TARGET_REFERENCE, PATH_CONTRACT_MISMATCH ->
                    "草稿目標路徑驗證失敗，請手動建立草稿並檢查目標";
        };
    }
}
