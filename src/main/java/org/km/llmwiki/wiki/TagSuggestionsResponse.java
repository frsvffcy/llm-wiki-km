package org.km.llmwiki.wiki;

import java.util.List;

/**
 * #569 post-upload organize surface 的唯讀建議投影。
 *
 * <p>Ephemeral suggestion：不持久化、不影響 retrieval；呼叫端不得將其視為 durable
 * classification。{@code current=false} 表示文件在分析後曾變動，建議已 stale，
 * 不得沿用於任何寫入決策（寫入仍走 proposal tags PATCH 的當下驗證）。
 */
public record TagSuggestionsResponse(long documentId, Long analysisId, String analyzedAt, boolean current,
                                     SuggestionFreshness freshness, List<CandidateTagSuggestion> suggestions) {

    public enum SuggestionFreshness {
        /** 綁定的分析仍是文件目前修訂的最新成功分析。 */
        CURRENT,
        /** 文件在分析後曾變動（updated_at 晚於分析建立時間）；建議僅供參考。 */
        DOCUMENT_CHANGED_AFTER_ANALYSIS,
        /** 文件尚無成功的分析；無建議可顯示。 */
        NO_ANALYSIS
    }

    public record CandidateTagSuggestion(long candidateId, int candidateNo, String title, String candidateType,
                                         double confidence, String summary, String rationale,
                                         String suggestedPageType, List<String> tags, TagsOrigin tagsOrigin) {
    }

    public enum TagsOrigin {
        /** 該候選尚無 analysis 提案攜帶 tags。 */
        NONE,
        /** 來自該候選最新一筆非 REJECTED analysis 提案的目前 tags（含可能的人工調整）。 */
        PROPOSAL
    }
}
