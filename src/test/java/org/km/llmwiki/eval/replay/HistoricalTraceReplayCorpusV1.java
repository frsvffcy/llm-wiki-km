package org.km.llmwiki.eval.replay;

import java.util.List;

/**
 * 版本化 historical trace replay corpus（{@code historical-trace-replay-corpus-v1}，議題 #520）。
 *
 * <p>每一筆 trace 只保存可稽核的 structured metadata／aggregate，不保存 raw private conversation、
 * provider raw payload、secret、absolute local path 或完整 chain-of-thought。欄位證據強度沿用
 * #509 discipline：{@link EvidenceStrength#MEASURED} 為 GitHub Issue／PR／CI 留下的可重驗記錄；
 * {@link EvidenceStrength#DERIVED_PROXY} 為分析者依標題／body／diff 歸納的判斷（例如 task shape、
 * subsystem、trigger 分類）；{@link EvidenceStrength#UNOBSERVED} 為歷史不支援、不得補猜的分支。
 *
 * <p>executor／model／effort／cost／tool-call／elapsed 在既有 Issue／PR 治理中從未被可靠記錄，
 * 因此全體標示 {@code UNKNOWN}，不得推測。此 corpus 為 developer-workflow evaluation plane，
 * 不具 production authority，不取代 source／tests／CI／Completion Audit。
 */
final class HistoricalTraceReplayCorpusV1 {

    static final String VERSION = "historical-trace-replay-corpus-v1";
    static final String UNKNOWN = "UNKNOWN";

    /** Corpus 快照的 trace 總數下限（#520 AC：至少 12 個完成的歷史 Issue／PR traces）。 */
    static final int MIN_TRACE_COUNT = 12;

    private HistoricalTraceReplayCorpusV1() {
    }

    /** 沿用 #509 的證據強度分類。 */
    enum EvidenceStrength {
        MEASURED,
        DERIVED_PROXY,
        UNOBSERVED
    }

    /** Replay split 單位永遠是完整 Issue／PR trace；lineage group 不得跨 split。 */
    enum Split {
        TUNE,
        HOLDOUT,
        CANARY
    }

    /**
     * 單一完整 Issue／PR 工作單元的 structured trace。
     *
     * @param issueNumber                GitHub Issue 編號（MEASURED）
     * @param complexityLevel            Issue title 的 {@code [L1]}～{@code [L5]}（MEASURED）
     * @param shortTitle                 簡短任務描述（MEASURED，源自 title）
     * @param taskShape                  任務形狀分類（DERIVED_PROXY，分析者歸納）
     * @param subsystem                  受影響子系統（DERIVED_PROXY，分析者歸納）
     * @param lineageGroup               root-cause／主題 lineage 群組，同組不得跨 split
     * @param split                      所屬 replay split
     * @param mergedPr                   已 merge 的 PR 編號（MEASURED）
     * @param auditVerdict               Completion Audit 結論（MEASURED，源自 Issue comment）
     * @param ciGateGreen                PR Gate 是否全綠（MEASURED）
     * @param hadCorrectiveLoop          歷史是否出現 corrective／retry loop（MEASURED）
     * @param loopEvidence               loop 的可驗證描述（MEASURED；無 loop 時為空）
     * @param loopRequiresProactiveReview 該 loop 是否屬於事前 review 可合理攔截的類型
     *                                  （DERIVED_PROXY，分析者判斷；為 simulator 唯一可判
     *                                   MISS 的依據，不得外推成因果保證）
     * @param hadIndependentChallenge    歷史是否實際執行過 independent challenge／fresh
     *                                   second pass（MEASURED）
     * @param touchesContractOrSecurity  是否觸及 contract／security／governance 邊界
     *                                  （DERIVED_PROXY，P3 trigger 用）
     * @param crossSubsystem             是否跨子系統（DERIVED_PROXY，P3 trigger 用）
     */
    record HistoricalTrace(int issueNumber, String complexityLevel, String shortTitle,
                           String taskShape, String subsystem, String lineageGroup, Split split,
                           int mergedPr, String auditVerdict, boolean ciGateGreen,
                           boolean hadCorrectiveLoop, String loopEvidence,
                           boolean loopRequiresProactiveReview, boolean hadIndependentChallenge,
                           boolean touchesContractOrSecurity, boolean crossSubsystem) {
    }

    static List<HistoricalTrace> traces() {
        return List.of(
                // ---- TUNE split（8）：CRG、release-v020、language-governance lineage 各自完整保留 ----
                new HistoricalTrace(504, "L3", "統一公開 API error.message 為繁體中文",
                        "bounded-corrective", "web-rest-error-contract", "language-governance",
                        Split.TUNE, 507, "FULL GO", true,
                        true, "首輪 audit 判 NO-GO for DONE（時序問題），merge #507 後重審 FULL GO",
                        true, false, true, false),
                new HistoricalTrace(505, "L3", "code-review-graph 歷史重播 benchmark",
                        "evaluation-benchmark", "developer-code-intelligence", "code-review-graph",
                        Split.TUNE, 508, "FULL GO", true,
                        false, "", false, true, true, false),
                new HistoricalTrace(509, "L2", "校正 #505 matched A/B 成本證據語意",
                        "evaluation-corrective", "developer-code-intelligence", "code-review-graph",
                        Split.TUNE, 510, "FULL GO", true,
                        false, "", false, true, true, false),
                new HistoricalTrace(511, "L5", "v0.2.0 release readiness 收斂",
                        "release-readiness", "release-governance", "release-v020",
                        Split.TUNE, 516, "FULL GO", true,
                        false, "", false, true, true, true),
                new HistoricalTrace(513, "L4", "exact candidate 跨產品旅程 acceptance",
                        "release-acceptance", "release-acceptance", "release-v020",
                        Split.TUNE, 513, "FULL GO", true,
                        false, "", false, false, true, true),
                new HistoricalTrace(515, "L3", "評估 Hindsight agent memory",
                        "evaluation-research", "agent-memory-evaluation", "agent-memory",
                        Split.TUNE, 516, "FULL GO", true,
                        false, "", false, false, false, false),
                new HistoricalTrace(519, "L3", "評估 Dream-RSI replay orchestration",
                        "evaluation-research", "agent-workflow-evaluation", "dream-rsi",
                        Split.TUNE, 521, "FULL GO", true,
                        false, "", false, false, false, false),
                // ---- HOLDOUT split（4）：與 tune 無 lineage 重疊 ----
                new HistoricalTrace(469, "L2", "superseded source citation 未 fail-closed",
                        "bounded-bug", "ask-proposal-ingress", "ask-ingress",
                        Split.HOLDOUT, 470, "FULL GO", true,
                        false, "", false, false, true, false),
                new HistoricalTrace(472, "L4", "補齊 acceptance 並重驗 trigger 決策",
                        "corrective-acceptance", "acceptance-validation", "acceptance-v020",
                        Split.HOLDOUT, 473, "FULL GO", true,
                        false, "", false, false, true, true),
                new HistoricalTrace(479, "L2", "瀏覽器分頁沿用舊版 static JS",
                        "bounded-bug", "browser-cache", "browser-platform",
                        Split.HOLDOUT, 480, "FULL GO", true,
                        true, "PR metadata 初次失敗（body 誤引 PR 號），修正後 rerun 全綠",
                        false, false, false, false),
                new HistoricalTrace(490, "L1", "Preview／Diff 誤用 HTTP method",
                        "bounded-bug", "browser-review-workbench", "browser-governance-ui",
                        Split.HOLDOUT, 491, "FULL GO", true,
                        false, "", false, false, false, false),
                new HistoricalTrace(501, "L2", "語言與術語檢查納入 PR Gate",
                        "ci-governance", "ci-language-governance", "language-governance",
                        Split.TUNE, 502, "FULL GO", true,
                        false, "", false, false, true, false),
                // ---- CANARY（1）：tune／holdout 選型完全未使用的 fresh trace ----
                new HistoricalTrace(517, "L2", "Inbox mutation 後清單未自動刷新",
                        "bounded-bug", "browser-inbox", "browser-platform-inbox",
                        Split.CANARY, 518, "FULL GO", true,
                        false, "", false, false, false, false));
    }

    static List<HistoricalTrace> tune() {
        return traces().stream().filter(trace -> trace.split() == Split.TUNE).toList();
    }

    static List<HistoricalTrace> holdout() {
        return traces().stream().filter(trace -> trace.split() == Split.HOLDOUT).toList();
    }

    static List<HistoricalTrace> canary() {
        return traces().stream().filter(trace -> trace.split() == Split.CANARY).toList();
    }
}
