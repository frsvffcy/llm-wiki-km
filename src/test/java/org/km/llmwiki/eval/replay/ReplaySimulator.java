package org.km.llmwiki.eval.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.km.llmwiki.eval.replay.HistoricalTraceReplayCorpusV1.EvidenceStrength;
import org.km.llmwiki.eval.replay.HistoricalTraceReplayCorpusV1.HistoricalTrace;
import org.km.llmwiki.eval.replay.HistoricalTraceReplayCorpusV1.Split;
import org.km.llmwiki.eval.replay.RoutingPolicySetV1.Proactive;
import org.km.llmwiki.eval.replay.RoutingPolicySetV1.RoutingPolicy;

/**
 * 確定性離線 replay simulator（議題 #520，Dream-RSI pattern 的 bounded 移植）。
 *
 * <p>本 simulator <strong>不生成歷史沒有發生的 model output</strong>：它只能在 recorded
 * decision／evidence support 上評估某 policy 是否已可停止、是否需要升級、是否需要
 * reviewer／challenger、是否多做無效 evidence collection、是否漏掉後來證明必要的
 * correctness evidence。歷史不支援的 transition 一律標為 {@code UNOBSERVED}，不得補猜
 * reward／outcome。
 *
 * <p>判定規則（全部 deterministic，詳見 tracked evaluation）：
 *
 * <ul>
 *   <li>所有 policy 共用 reactive escalation 與 CI gate：歷史 loop 皆假設被升級處理，
 *       CI retry 由 gate 本身攔截，不歸因於事前 review。</li>
 *   <li>{@code missedCritical}（hard fail）只在單一條件成立：trace 有 corrective loop、
 *       該 loop 被標記為事前 review 可合理攔截（{@code loopRequiresProactiveReview}，
 *       DERIVED_PROXY），而該 policy 恰好未排程事前工作。</li>
 *   <li>{@code unobservedRisk}：policy 未排程事前工作、歷史亦無 loop，且 trace 為 L3 以上
 *       時——無法得知 review 是否會發現問題，不得把「無 loop」寫成「review 無用」。</li>
 *   <li>{@code wouldStop}：policy 未排程事前工作且歷史無 loop 時為 true；有 loop 或有排程
 *       時為 false；凡 {@code unobservedRisk} 為 true 者 {@code wouldStop} 恆為 false，
 *       避免把未觀測分支解讀成可停止證據。</li>
 * </ul>
 *
 * <p>Cost 比較（事前 REVIEW／CHALLENGE 計數）為 DERIVED_PROXY：真實 model／token／elapsed
 * 在 corpus 中皆為 UNKNOWN，計數只代表政策排程量，不代表實測節省。
 */
final class ReplaySimulator {

    private ReplaySimulator() {
    }

    /**
     * 單一 trace × policy 的 replay 判定。
     *
     * @param issueNumber     trace 的 Issue 編號
     * @param policyId        policy 代號
     * @param proactive       該 policy 對此 trace 的事前排程
     * @param wouldStop       在已觀測 support 下是否可停止（UNOBSERVED 分支恆為 false）
     * @param missedCritical  是否漏掉 correctness-critical evidence（hard fail）
     * @param unobservedRisk  是否落在歷史不支援的分支（不得解讀成通過或失敗證據）
     * @param strength        本判定的證據強度
     * @param rationale       判定理由（可稽核短句）
     */
    record ReplayVerdict(int issueNumber, String policyId, Proactive proactive, boolean wouldStop,
                         boolean missedCritical, boolean unobservedRisk, EvidenceStrength strength,
                         String rationale) {
    }

    /** 單一 split 的 policy 彙總：correctness gate 結果＋排程量 proxy＋未觀測計數。 */
    record SplitSummary(String policyId, Split split, boolean gatePassed, int missCount,
                        int proactiveReviews, int proactiveChallenges, int unobservedCount) {
    }

    static ReplayVerdict replay(HistoricalTrace trace, RoutingPolicy policy) {
        Proactive proactive = RoutingPolicySetV1.proactiveFor(policy, trace);
        boolean scheduled = proactive != Proactive.NONE;

        if (trace.hadCorrectiveLoop() && trace.loopRequiresProactiveReview() && !scheduled) {
            return new ReplayVerdict(trace.issueNumber(), policy.id(), proactive, false, true,
                    false, EvidenceStrength.DERIVED_PROXY,
                    "歷史 loop 屬事前 review 可攔截類型而本 policy 未排程：hard fail");
        }
        if (!scheduled && !trace.hadCorrectiveLoop() && isSenior(trace)) {
            return new ReplayVerdict(trace.issueNumber(), policy.id(), proactive, false, false,
                    true, EvidenceStrength.UNOBSERVED,
                    "未排程事前工作且歷史無 loop：review 是否有發現不可知，標 UNOBSERVED");
        }
        boolean wouldStop = !scheduled && !trace.hadCorrectiveLoop();
        return new ReplayVerdict(trace.issueNumber(), policy.id(), proactive, wouldStop, false,
                false, trace.hadCorrectiveLoop()
                        ? EvidenceStrength.MEASURED
                        : EvidenceStrength.DERIVED_PROXY,
                trace.hadCorrectiveLoop()
                        ? "歷史 loop 由共用 reactive escalation／CI gate 覆蓋，無事前缺口"
                        : "已觀測 support 下無缺口；停止判斷僅限 recorded evidence");
    }

    private static boolean isSenior(HistoricalTrace trace) {
        return switch (trace.complexityLevel()) {
            case "L3", "L4", "L5" -> true;
            default -> false;
        };
    }

    static List<ReplayVerdict> replayAll(List<HistoricalTrace> traces, RoutingPolicy policy) {
        List<ReplayVerdict> verdicts = new ArrayList<>();
        for (HistoricalTrace trace : traces) {
            verdicts.add(replay(trace, policy));
        }
        return List.copyOf(verdicts);
    }

    static SplitSummary summarize(RoutingPolicy policy, Split split,
            List<ReplayVerdict> verdicts) {
        int miss = 0;
        int reviews = 0;
        int challenges = 0;
        int unobserved = 0;
        for (ReplayVerdict verdict : verdicts) {
            if (verdict.missedCritical()) {
                miss++;
            }
            if (verdict.unobservedRisk()) {
                unobserved++;
            }
            if (verdict.proactive() == Proactive.REVIEW) {
                reviews++;
            } else if (verdict.proactive() == Proactive.CHALLENGE) {
                challenges++;
            }
        }
        return new SplitSummary(policy.id(), split, miss == 0, miss, reviews, challenges,
                unobserved);
    }

    /** 依 tune → holdout → canary 順序對全部 policy 執行完整 replay，key 為 policy 代號。 */
    static Map<String, List<SplitSummary>> runCalibration() {
        Map<String, List<SplitSummary>> result = new LinkedHashMap<>();
        for (RoutingPolicy policy : RoutingPolicySetV1.all()) {
            List<SplitSummary> summaries = List.of(
                    summarize(policy, Split.TUNE,
                            replayAll(HistoricalTraceReplayCorpusV1.tune(), policy)),
                    summarize(policy, Split.HOLDOUT,
                            replayAll(HistoricalTraceReplayCorpusV1.holdout(), policy)),
                    summarize(policy, Split.CANARY,
                            replayAll(HistoricalTraceReplayCorpusV1.canary(), policy)));
            result.put(policy.id(), summaries);
        }
        return Map.copyOf(result);
    }

    /**
     * 依 correctness-first objective 做選型：先過 hard gate（tune＋holdout 無 MISS），再比
     * UNOBSERVED 風險，最後比排程量 proxy。回傳勝出 policy 代號；無通過者回傳空字串。
     */
    static String selectWinner(Map<String, List<SplitSummary>> calibration) {
        String winner = "";
        int bestUnobserved = Integer.MAX_VALUE;
        int bestProactive = Integer.MAX_VALUE;
        for (RoutingPolicy policy : RoutingPolicySetV1.all()) {
            List<SplitSummary> summaries = calibration.get(policy.id());
            SplitSummary tune = summaries.get(0);
            SplitSummary holdout = summaries.get(1);
            if (!tune.gatePassed() || !holdout.gatePassed()) {
                continue;
            }
            int unobserved = tune.unobservedCount() + holdout.unobservedCount();
            int proactive = tune.proactiveReviews() + tune.proactiveChallenges()
                    + holdout.proactiveReviews() + holdout.proactiveChallenges();
            if (unobserved < bestUnobserved
                    || (unobserved == bestUnobserved && proactive < bestProactive)) {
                winner = policy.id();
                bestUnobserved = unobserved;
                bestProactive = proactive;
            }
        }
        return winner;
    }
}
