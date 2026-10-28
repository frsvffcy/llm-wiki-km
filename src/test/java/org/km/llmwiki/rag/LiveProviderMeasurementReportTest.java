package org.km.llmwiki.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.answer.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.km.llmwiki.rag.LiveProviderMeasurementReport.Decision.*;

@Tag("contract")
class LiveProviderMeasurementReportTest {
    private static final List<String> CASES = List.of("conflict", "completeness");

    @Test
    @DisplayName("完整且唯一的案例與次數才可判定不需變更")
    void completeCoverageRequired() {
        var rows = completeRows();
        assertThat(decide(rows, true)).isEqualTo(NO_CHANGE);
        assertThat(decide(rows, false)).isEqualTo(UNRESOLVED);
        assertThat(decide(List.of(), true)).isEqualTo(UNRESOLVED);
        assertThat(decide(rows.subList(0, 3), true)).isEqualTo(UNRESOLVED);
        rows.set(3, rows.get(0));
        assertThat(decide(rows, true)).isEqualTo(UNRESOLVED);
        rows = completeRows();
        rows.get(0).put("caseId", "foreign");
        assertThat(decide(rows, true)).isEqualTo(UNRESOLVED);
        rows = completeRows();
        rows.get(0).put("attempt", 3);
        assertThat(decide(rows, true)).isEqualTo(UNRESOLVED);
    }

    @Test
    @DisplayName("提供者失敗優先於品質失敗且不受順序影響")
    void failurePrecedenceIsOrderIndependent() {
        var rows = completeRows();
        rows.get(0).put("verdict", "PARTIAL");
        assertThat(decide(rows, true)).isEqualTo(ANSWER_FOLLOW_UP);
        rows.get(3).put("status", "PROVIDER_FAILURE");
        assertThat(decide(rows, true)).isEqualTo(UNRESOLVED);
        java.util.Collections.reverse(rows);
        assertThat(decide(rows, true)).isEqualTo(UNRESOLVED);
    }

    @Test
    @DisplayName("未知結果與缺欄位不得假通過，錯誤拒答須後續處理")
    void unknownAndAbstention() {
        var rows = completeRows();
        rows.get(0).put("providerInsufficientEvidence", true);
        assertThat(decide(rows, true)).isEqualTo(ANSWER_FOLLOW_UP);
        for (String verdict : List.of("UNOBSERVED", "UNKNOWN", "")) {
            rows.get(0).put("verdict", verdict);
            assertThat(decide(rows, true)).isEqualTo(UNRESOLVED);
        }
        rows = completeRows();
        rows.get(0).remove("providerInsufficientEvidence");
        assertThat(decide(rows, true)).isEqualTo(UNRESOLVED);
    }

    @Test
    @DisplayName("摘要反映實際投影文字與順序，不洩漏原文或路徑")
    void requestDigestTracksActualProjection() throws Exception {
        var first = block("E1", "secret-sentinel", "private-path-sentinel");
        var second = block("E2", "第二份證據", "vault/second.md");
        var base = stamp(List.of(first, second), 4000, "policy-v1");
        assertThat(stamp(List.of(second, first), 4000, "policy-v1").get("contextDigest"))
                .isNotEqualTo(base.get("contextDigest"));
        assertThat(stamp(List.of(block("E1", "截短文字", "private-path-sentinel"), second), 4000, "policy-v1")
                .get("contextDigest")).isNotEqualTo(base.get("contextDigest"));
        assertThat(stamp(List.of(first, second), 2000, "policy-v1")).isNotEqualTo(base);
        assertThat(stamp(List.of(first, second), 4000, "policy-v2")).isNotEqualTo(base);
        assertThat(new ObjectMapper().writeValueAsString(base))
                .doesNotContain("secret-sentinel", "private-path-sentinel", "原始問題");
    }

    @Test
    @DisplayName("報告記錄實際提供者生成參數，且不包含其他敏感設定")
    void providerGenerationTracksConfiguredValues() throws Exception {
        var properties = new org.km.llmwiki.ai.answer.provider.openai.OpenAiCompatibleAnswerProperties();
        properties.setMaxOutputTokens(789);
        properties.setTemperature(0.37);
        properties.setApiKey("credential-sentinel");
        properties.setBaseUrl("https://endpoint-sentinel.invalid/private-path-sentinel");
        var baseline = LiveProviderMeasurementReport.providerStamp(properties);
        assertThat(baseline).containsExactlyInAnyOrderEntriesOf(Map.of("max_tokens", 789, "temperature", 0.37));
        properties.setMaxOutputTokens(1234);
        assertThat(LiveProviderMeasurementReport.providerStamp(properties))
                .isNotEqualTo(baseline).containsEntry("max_tokens", 1234);
        properties.setMaxOutputTokens(789);
        properties.setTemperature(0.91);
        assertThat(LiveProviderMeasurementReport.providerStamp(properties))
                .isNotEqualTo(baseline).containsEntry("temperature", 0.91);
        String json = new ObjectMapper().writeValueAsString(baseline);
        assertThat(json).doesNotContain("credential-sentinel", "endpoint-sentinel", "private-path-sentinel");
        var parsed = new ObjectMapper().readTree(json);
        assertThat(parsed.get("max_tokens").intValue()).isEqualTo(789);
        assertThat(parsed.get("temperature").doubleValue()).isEqualTo(0.37);
    }

    @Test
    @DisplayName("啟動失敗只顯示本次識別的報告，不顯示殘留成功報告")
    void launcherRejectsStaleReport(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var bin = java.nio.file.Files.createDirectory(temp.resolve("bin"));
        var report = temp.resolve("target/quality-reports/grounded-answer-live-provider-v1.md");
        java.nio.file.Files.createDirectories(report.getParent());
        java.nio.file.Files.writeString(report, "OLD_SUCCESS_SENTINEL\n- runId: old\n");
        var mvn = bin.resolve("mvn");
        java.nio.file.Files.writeString(mvn, "#!/bin/sh\nexit 7\n");
        assertThat(mvn.toFile().setExecutable(true)).isTrue();
        var script = java.nio.file.Path.of("scripts/run-grounded-answer-live-provider-measurement.sh")
                .toAbsolutePath();
        for (boolean fresh : List.of(false, true)) {
            if (fresh) {
                java.nio.file.Files.writeString(mvn, """
                        #!/bin/sh
                        for arg in "$@"; do
                          case "$arg" in -Dmeasurement.runId=*) run_id="${arg#*=}" ;; esac
                        done
                        printf '%s\n' "- runId: $run_id" 'CURRENT_FAILURE_SENTINEL' > target/quality-reports/grounded-answer-live-provider-v1.md
                        exit 7
                        """);
            }
            var builder = new ProcessBuilder("/bin/bash", script.toString()).directory(temp.toFile())
                    .redirectErrorStream(true);
            builder.environment().clear();
            builder.environment().putAll(Map.of("PATH", bin + ":/usr/bin:/bin",
                    "ANSWER_PROVIDER_ENABLED", "true", "ANSWER_PROVIDER_MODEL", "offline-fixture",
                    "OPENAI_API_KEY", "test-only-not-a-credential"));
            var process = builder.start();
            assertThat(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).isEqualTo(7);
            assertThat(output).doesNotContain("OLD_SUCCESS_SENTINEL", "test-only-not-a-credential");
            if (fresh) assertThat(output).contains("CURRENT_FAILURE_SENTINEL");
            else assertThat(output).contains("本次未產生可識別的新報告");
        }
    }

    private static Map<String, Object> stamp(List<AnswerContextBlock> blocks, int output, String policy) {
        return LiveProviderMeasurementReport.requestStamp(new AnswerRequest("原始問題",
                new AnswerContext(blocks), new AnswerGenerationOptions(output)), policy);
    }

    private static AnswerContextBlock block(String id, String text, String path) {
        return new AnswerContextBlock(id, EvidenceKind.WIKI, "WIKI:" + id, text, false,
                "same-canonical-hash", new AnswerContextProvenance.Wiki("標題", path, 1));
    }

    private static LiveProviderMeasurementReport.Decision decide(List<Map<String, Object>> rows, boolean completed) {
        return LiveProviderMeasurementReport.decide(CASES, 2, rows, completed);
    }

    private static List<Map<String, Object>> completeRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : CASES) for (int attempt = 1; attempt <= 2; attempt++) {
            rows.add(new LinkedHashMap<>(Map.of("caseId", id, "attempt", attempt,
                    "status", "MEASURED", "verdict", "COMPLETE", "retrievalComplete", true,
                    "providerInsufficientEvidence", false)));
        }
        return rows;
    }
}
