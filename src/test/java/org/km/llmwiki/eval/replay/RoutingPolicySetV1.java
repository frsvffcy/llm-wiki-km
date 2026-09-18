package org.km.llmwiki.eval.replay;

import java.util.List;

import org.km.llmwiki.eval.replay.HistoricalTraceReplayCorpusV1.HistoricalTrace;

/**
 * 版本化 routing／escalation／challenge policy 候選集合（{@code routing-policy-set-v1}，議題 #520）。
 *
 * <p>current policy（P0）必須是候選 baseline，避免離線選型偷偷退化。四個 policy 只決定
 * <strong>事前（proactive）review／challenge 是否排程</strong>；reactive escalation（歷史出現
 * corrective loop／CI retry 時升級）與 CI gate 為所有 policy 共用前提，由
 * {@link ReplaySimulator} 統一施加，不列為 policy 差異。
 *
 * <p>Policy 版本與 objective 版本（{@code correctness-first-v1}）綁定：correctness hard gate
 * 優先，cost／token／elapsed 只在 correctness 不退化後排序。本集合只做離線比較，不具備
 * 修改 {@code model-routing.md} 的授權；任何 routing 更新仍需 fresh online canary＋人工
 * review＋normal PR。
 */
final class RoutingPolicySetV1 {

    static final String VERSION = "routing-policy-set-v1";
    static final String OBJECTIVE_VERSION = "correctness-first-v1";

    private RoutingPolicySetV1() {
    }

    /** 事前排程強度：NONE 為 self-review／checklist，REVIEW 為一般 review，CHALLENGE 為獨立挑戰。 */
    enum Proactive {
        NONE,
        REVIEW,
        CHALLENGE
    }

    /**
     * 單一候選 orchestration policy。
     *
     * @param id          policy 代號（P0 為 current baseline）
     * @param name        簡短名稱
     * @param description 排程規則描述
     */
    record RoutingPolicy(String id, String name, String description) {
    }

    static final RoutingPolicy P0_CURRENT =
            new RoutingPolicy("P0", "current-baseline", "L1／L2 self-review；L3 明確 review；L4／L5 獨立 challenge");
    static final RoutingPolicy P1_DELAYED =
            new RoutingPolicy("P1", "delayed-escalation", "L1～L3 皆 self-review（L3 不再事前 review）；僅 L4／L5 獨立 challenge");
    static final RoutingPolicy P2_EARLY =
            new RoutingPolicy("P2", "early-challenge", "L1 self-review；L2 事前 review；L3 以上一律獨立 challenge");
    static final RoutingPolicy P3_TRIGGERED =
            new RoutingPolicy("P3", "evidence-triggered", "L4／L5 獨立 challenge；L1～L3 僅在觸及 contract／security／governance 邊界或跨子系統時事前 review");

    static List<RoutingPolicy> all() {
        return List.of(P0_CURRENT, P1_DELAYED, P2_EARLY, P3_TRIGGERED);
    }

    /**
     * 決定某 policy 對某 trace 是否排程事前 review／challenge。
     *
     * <p>此函數為純規則投影（DERIVED_PROXY），不重演歷史 executor 行為，不生成未觀測的
     * model output。
     */
    static Proactive proactiveFor(RoutingPolicy policy, HistoricalTrace trace) {
        return switch (policy.id()) {
            case "P0" -> switch (trace.complexityLevel()) {
                case "L1", "L2" -> Proactive.NONE;
                case "L3" -> Proactive.REVIEW;
                default -> Proactive.CHALLENGE;
            };
            case "P1" -> switch (trace.complexityLevel()) {
                case "L4", "L5" -> Proactive.CHALLENGE;
                default -> Proactive.NONE;
            };
            case "P2" -> switch (trace.complexityLevel()) {
                case "L1" -> Proactive.NONE;
                case "L2" -> Proactive.REVIEW;
                default -> Proactive.CHALLENGE;
            };
            case "P3" -> {
                if (trace.complexityLevel().equals("L4") || trace.complexityLevel().equals("L5")) {
                    yield Proactive.CHALLENGE;
                }
                if (trace.touchesContractOrSecurity() || trace.crossSubsystem()) {
                    yield Proactive.REVIEW;
                }
                yield Proactive.NONE;
            }
            default -> throw new IllegalArgumentException("未知的 routing policy：" + policy.id());
        };
    }
}
