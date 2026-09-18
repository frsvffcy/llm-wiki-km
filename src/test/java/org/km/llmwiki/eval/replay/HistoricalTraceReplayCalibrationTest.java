package org.km.llmwiki.eval.replay;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

import org.km.llmwiki.eval.replay.HistoricalTraceReplayCorpusV1.HistoricalTrace;
import org.km.llmwiki.eval.replay.HistoricalTraceReplayCorpusV1.Split;
import org.km.llmwiki.eval.replay.ReplaySimulator.ReplayVerdict;
import org.km.llmwiki.eval.replay.ReplaySimulator.SplitSummary;
import org.km.llmwiki.eval.replay.RoutingPolicySetV1.RoutingPolicy;

/**
 * Historical trace replay calibration（議題 #520）。
 *
 * <p>以 12 個完成的歷史 Issue／PR traces（tune 8＋holdout 4）與 1 組 fresh canary，
 * 離線比較 current baseline（P0）與 3 個 candidate orchestration policies（P1～P3）。
 * 全部判定 deterministic、可重跑；raw transcript 不進 Git，aggregate 報告寫入
 * git-ignored 的 {@code target/quality-reports/}，長期決策收斂於 tracked evaluation。
 *
 * <p>本 test 為 evaluation plane，不具 production authority；harness 缺席或 corpus 不足時
 * baseline workflow 不受影響（見 {@link #harnessNeverTouchesProductionSources()}）。
 */
@Tag("unit")
class HistoricalTraceReplayCalibrationTest {

    private static final Path TRACKED_EVALUATION =
            Path.of("docs/evaluations/2026-09-18-historical-trace-replay-calibration.md");
    private static final Path REPORT_DIR = Path.of("target/quality-reports");
    private static final Path REPORT_JSON =
            REPORT_DIR.resolve("historical-trace-replay-calibration.json");
    private static final Path REPORT_MD =
            REPORT_DIR.resolve("historical-trace-replay-calibration.md");

    @Test
    void corpusMeetsMinimumSizeAndLevelCoverage() {
        List<HistoricalTrace> traces = HistoricalTraceReplayCorpusV1.traces();
        assertThat(traces).hasSizeGreaterThanOrEqualTo(HistoricalTraceReplayCorpusV1.MIN_TRACE_COUNT);
        assertThat(HistoricalTraceReplayCorpusV1.tune()).hasSize(8);
        assertThat(HistoricalTraceReplayCorpusV1.holdout()).hasSize(4);
        assertThat(HistoricalTraceReplayCorpusV1.canary()).hasSize(1);

        Set<String> levels = new HashSet<>();
        for (HistoricalTrace trace : traces) {
            levels.add(trace.complexityLevel());
        }
        assertThat(levels).as("corpus 必須涵蓋 L1～L5")
                .containsExactlyInAnyOrder("L1", "L2", "L3", "L4", "L5");

        long bounded = traces.stream().filter(trace -> trace.taskShape().contains("bounded")).count();
        assertThat(bounded).as("bounded bug 覆蓋").isGreaterThanOrEqualTo(3);
        long governance = traces.stream()
                .filter(trace -> trace.taskShape().contains("governance")
                        || trace.subsystem().contains("governance"))
                .count();
        assertThat(governance).as("CI／governance 覆蓋").isGreaterThanOrEqualTo(2);
        long release = traces.stream().filter(trace -> trace.taskShape().contains("release")).count();
        assertThat(release).as("release audit 覆蓋（concurrency／lifecycle 專屬 trace 為已知缺口，見 evaluation）")
                .isGreaterThanOrEqualTo(2);
        long intelligence = traces.stream()
                .filter(trace -> trace.subsystem().contains("intelligence")
                        || trace.subsystem().contains("acceptance")
                        || trace.subsystem().contains("ingress"))
                .count();
        assertThat(intelligence).as("integration／graph 相關覆蓋").isGreaterThanOrEqualTo(2);
    }

    @Test
    void corpusRecordsNoInventedModelEffortOrCost() {
        // executor／model／effort／cost／tool-call／elapsed 在 Issue／PR 治理中從未可靠記錄；
        // schema 結構上就不得攜帶這些欄位，避免任何推測值混入 corpus。
        Set<String> forbidden = Set.of("model", "effort", "cost", "tokencount", "toolcall",
                "toolcalls", "elapsed", "tokens");
        for (var component : HistoricalTrace.class.getRecordComponents()) {
            assertThat(forbidden)
                    .as("trace schema 不得含有可推測欄位：" + component.getName())
                    .doesNotContain(component.getName().toLowerCase());
        }
        assertThat(HistoricalTraceReplayCorpusV1.UNKNOWN).isEqualTo("UNKNOWN");
    }

    @Test
    void lineageGroupsNeverSplitAcrossTuneAndHoldout() {
        Map<String, Set<Split>> groupSplits = new HashMap<>();
        for (HistoricalTrace trace : HistoricalTraceReplayCorpusV1.traces()) {
            if (trace.split() == Split.CANARY) {
                continue;
            }
            groupSplits.computeIfAbsent(trace.lineageGroup(), key -> new HashSet<>())
                    .add(trace.split());
        }
        List<String> offenders = new ArrayList<>();
        groupSplits.forEach((group, splits) -> {
            if (splits.size() > 1) {
                offenders.add(group + " -> " + splits);
            }
        });
        assertThat(offenders).as("同一 lineage 不得跨 tune／holdout（canary 獨立）").isEmpty();
    }

    @Test
    void currentPolicyIsCandidateBaseline() {
        List<String> ids = RoutingPolicySetV1.all().stream().map(RoutingPolicy::id).toList();
        assertThat(ids).containsExactly("P0", "P1", "P2", "P3");
    }

    @Test
    void simulatorNeverEmitsOutcomeForUnobservedBranches() {
        for (RoutingPolicy policy : RoutingPolicySetV1.all()) {
            for (HistoricalTrace trace : HistoricalTraceReplayCorpusV1.traces()) {
                ReplayVerdict verdict = ReplaySimulator.replay(trace, policy);
                assertThat(verdict.strength()).as("每個判定必須標示證據強度").isNotNull();
                if (verdict.unobservedRisk()) {
                    assertThat(verdict.wouldStop())
                            .as("UNOBSERVED 不得解讀成可停止：" + trace.issueNumber() + "/" + policy.id())
                            .isFalse();
                    assertThat(verdict.missedCritical())
                            .as("UNOBSERVED 不得同時判 MISS：" + trace.issueNumber() + "/" + policy.id())
                            .isFalse();
                }
            }
        }
    }

    @Test
    void calibrationIsDeterministic() {
        Map<String, List<SplitSummary>> first = ReplaySimulator.runCalibration();
        Map<String, List<SplitSummary>> second = ReplaySimulator.runCalibration();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void tuneSplitFindingsMatchRecordedEvidence() {
        Map<String, List<SplitSummary>> calibration = ReplaySimulator.runCalibration();

        SplitSummary baseline = calibration.get("P0").get(0);
        assertThat(baseline.split()).isEqualTo(Split.TUNE);
        assertThat(baseline.gatePassed()).as("P0 必須通過 tune hard gate").isTrue();
        assertThat(baseline.missCount()).isZero();
        assertThat(baseline.unobservedCount()).as("P0 在 tune 不得有 UNOBSERVED 風險").isZero();
        assertThat(baseline.proactiveReviews()).isEqualTo(4);
        assertThat(baseline.proactiveChallenges()).isEqualTo(2);

        SplitSummary delayed = calibration.get("P1").get(0);
        assertThat(delayed.gatePassed()).as("P1 在 tune 必須因 #504 判 hard fail").isFalse();
        assertThat(delayed.missCount()).isEqualTo(1);

        SplitSummary early = calibration.get("P2").get(0);
        assertThat(early.gatePassed()).as("P2 通過 tune gate").isTrue();
        int baselineProactive =
                baseline.proactiveReviews() + baseline.proactiveChallenges();
        int earlyProactive = early.proactiveReviews() + early.proactiveChallenges();
        assertThat(earlyProactive)
                .as("P2 排程量必須高於 baseline（cost 從不壓過 correctness）")
                .isGreaterThan(baselineProactive);

        SplitSummary triggered = calibration.get("P3").get(0);
        assertThat(triggered.gatePassed()).as("P3 通過 tune gate").isTrue();
        assertThat(triggered.unobservedCount())
                .as("P3 在 docs-eval traces 留下 UNOBSERVED 風險").isGreaterThan(0);

        // P1 的唯一 MISS 必須是 #504（事前 review 可攔截的 loop），不得誤判其他 trace。
        List<ReplayVerdict> p1Verdicts =
                ReplaySimulator.replayAll(HistoricalTraceReplayCorpusV1.tune(),
                        RoutingPolicySetV1.P1_DELAYED);
        List<Integer> missed = p1Verdicts.stream().filter(ReplayVerdict::missedCritical)
                .map(ReplayVerdict::issueNumber).toList();
        assertThat(missed).containsExactly(504);
    }

    @Test
    void holdoutConfirmsSelectionWithoutLeakage() {
        Map<String, List<SplitSummary>> calibration = ReplaySimulator.runCalibration();
        for (RoutingPolicy policy : RoutingPolicySetV1.all()) {
            SplitSummary holdout = calibration.get(policy.id()).get(1);
            assertThat(holdout.split()).isEqualTo(Split.HOLDOUT);
        }
        // Holdout 無 L3 trace，P1 在此 split 無 MISS——此差異如實記錄，P1 仍因 tune hard
        // fail 被拒；selection 不得只看單一 split。
        assertThat(calibration.get("P0").get(1).gatePassed()).isTrue();
        assertThat(calibration.get("P1").get(1).gatePassed()).isTrue();
        assertThat(calibration.get("P3").get(1).gatePassed()).isTrue();
        assertThat(calibration.get("P2").get(1).gatePassed()).isTrue();
    }

    @Test
    void freshCanaryShowsNoGainFromExtraChallenge() {
        Map<String, List<SplitSummary>> calibration = ReplaySimulator.runCalibration();
        for (RoutingPolicy policy : RoutingPolicySetV1.all()) {
            SplitSummary canary = calibration.get(policy.id()).get(2);
            assertThat(canary.split()).isEqualTo(Split.CANARY);
            assertThat(canary.gatePassed())
                    .as(policy.id() + " 在 fresh canary（#517）不得有 MISS").isTrue();
        }
        // Canary 為 L2 bounded bug：只有 P2 排程額外 review，且歷史 single-pass FULL GO
        // 顯示該額外排程無可觀測增益。
        assertThat(calibration.get("P2").get(2).proactiveReviews()).isEqualTo(1);
        assertThat(calibration.get("P0").get(2).proactiveReviews()).isZero();
        assertThat(calibration.get("P0").get(2).proactiveChallenges()).isZero();
    }

    @Test
    void winnerKeepsCurrentPolicy() {
        // Correctness-first：P1 因 hard fail 出局；P3 帶 UNOBSERVED 風險；P2 與 P0 皆乾淨
        // 通過但 P2 排程量更高且 canary 無增益 → 勝出必須是 current baseline。
        assertThat(ReplaySimulator.selectWinner(ReplaySimulator.runCalibration())).isEqualTo("P0");
    }

    @Test
    void harnessNeverTouchesProductionSources() throws Exception {
        // Developer harness 缺席或不足時 baseline workflow 不受影響：production 不得依賴
        // test-scoped replay harness。
        Path productionRoot = Path.of("src/main/java/org/km/llmwiki");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(productionRoot)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                if (text.contains("eval.replay") || text.contains("eval/replay")) {
                    offenders.add(productionRoot.relativize(source).toString());
                }
            }
        }
        assertThat(offenders).as("production 不得引用 replay harness").isEmpty();
        // Harness 自身必須是純 Java 離線邏輯：不得引用 Spring、provider、SQLite 或 filesystem
        // mutation 邊界。
        Path harnessRoot = Path.of("src/test/java/org/km/llmwiki/eval/replay");
        List<Path> harnessFiles;
        try (Stream<Path> paths = Files.walk(harnessRoot)) {
            // 被測 harness 本體（corpus／policy／simulator）；本 test 自身的斷言字串不在掃描範圍。
            harnessFiles = paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().endsWith("Test.java"))
                    .toList();
        }
        assertThat(harnessFiles).hasSize(3);
        for (Path source : harnessFiles) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertThat(text)
                    .as(source.getFileName() + " 不得引入 Spring／provider／persistence")
                    .doesNotContain("org.springframework", "LlmClient", "EmbeddingClient",
                            "sqlite", "Files.write");
        }
        // Spring 註解亦不得出現在 harness（純 unit tier，無 application context）。
        for (Path source : harnessFiles) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertThat(text).doesNotContain("@SpringBootTest", "@Autowired", "@Service",
                    "@Component");
        }
        assertThat(Modifier.isFinal(HistoricalTraceReplayCorpusV1.class.getModifiers())).isTrue();
    }

    @Test
    void trackedEvaluationStaysConsistentWithSimulatorDecision() throws Exception {
        assertThat(TRACKED_EVALUATION).as("tracked evaluation 必須存在").exists();
        String evaluation = Files.readString(TRACKED_EVALUATION, StandardCharsets.UTF_8);
        assertThat(evaluation).contains(HistoricalTraceReplayCorpusV1.VERSION);
        assertThat(evaluation).contains(RoutingPolicySetV1.VERSION);
        assertThat(evaluation).contains(ReplaySimulator.selectWinner(ReplaySimulator.runCalibration()));
        assertThat(evaluation).contains("KEEP CURRENT");
    }

    @Test
    void writeEphemeralCalibrationReport() throws Exception {
        Map<String, List<SplitSummary>> calibration = ReplaySimulator.runCalibration();
        Files.createDirectories(REPORT_DIR);

        StringBuilder json = new StringBuilder(2048);
        json.append("{\"corpus\":\"").append(HistoricalTraceReplayCorpusV1.VERSION)
                .append("\",\"policies\":\"").append(RoutingPolicySetV1.VERSION)
                .append("\",\"objective\":\"").append(RoutingPolicySetV1.OBJECTIVE_VERSION)
                .append("\",\"winner\":\"").append(ReplaySimulator.selectWinner(calibration))
                .append("\",\"summaries\":[");
        boolean first = true;
        for (var entry : calibration.entrySet()) {
            for (SplitSummary summary : entry.getValue()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append("{\"policy\":\"").append(summary.policyId())
                        .append("\",\"split\":\"").append(summary.split())
                        .append("\",\"gatePassed\":").append(summary.gatePassed())
                        .append(",\"miss\":").append(summary.missCount())
                        .append(",\"reviews\":").append(summary.proactiveReviews())
                        .append(",\"challenges\":").append(summary.proactiveChallenges())
                        .append(",\"unobserved\":").append(summary.unobservedCount()).append('}');
            }
        }
        json.append("]}");
        Files.writeString(REPORT_JSON, json.toString(), StandardCharsets.UTF_8);

        StringBuilder md = new StringBuilder(2048);
        md.append("# Historical trace replay calibration（ephemeral report）\n\n")
                .append("corpus=").append(HistoricalTraceReplayCorpusV1.VERSION)
                .append(" policies=").append(RoutingPolicySetV1.VERSION)
                .append(" objective=").append(RoutingPolicySetV1.OBJECTIVE_VERSION)
                .append(" winner=").append(ReplaySimulator.selectWinner(calibration)).append("\n\n")
                .append("| policy | split | gate | miss | reviews | challenges | unobserved |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (var entry : calibration.entrySet()) {
            for (SplitSummary summary : entry.getValue()) {
                md.append("| ").append(summary.policyId()).append(" | ").append(summary.split())
                        .append(" | ").append(summary.gatePassed() ? "PASS" : "FAIL")
                        .append(" | ").append(summary.missCount())
                        .append(" | ").append(summary.proactiveReviews())
                        .append(" | ").append(summary.proactiveChallenges())
                        .append(" | ").append(summary.unobservedCount()).append(" |\n");
            }
        }
        md.append("\n本報告為 ephemeral aggregate（見 evaluations README §5），不進 Git；"
                + "長期決策以 tracked evaluation 為準。\n");
        Files.writeString(REPORT_MD, md.toString(), StandardCharsets.UTF_8);

        assertThat(REPORT_JSON).exists();
        assertThat(REPORT_MD).exists();
    }
}
