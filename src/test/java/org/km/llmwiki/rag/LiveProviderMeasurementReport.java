package org.km.llmwiki.rag;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.km.llmwiki.ai.answer.AnswerContextSerializer;
import org.km.llmwiki.ai.answer.AnswerRequest;
import org.km.llmwiki.ai.answer.GroundedAnswerPromptContract;
import org.km.llmwiki.ai.answer.provider.openai.OpenAiCompatibleAnswerProperties;

/** 真實提供者量測專用的離線決策與摘要；不改動產品契約。 */
final class LiveProviderMeasurementReport {
    enum Decision { UNRESOLVED, ANSWER_FOLLOW_UP, NO_CHANGE }

    static Decision decide(List<String> caseIds, int repetitions,
                           List<Map<String, Object>> rows, boolean completed) {
        if (!completed || repetitions < 1 || caseIds.isEmpty()
                || new HashSet<>(caseIds).size() != caseIds.size()
                || rows.size() != caseIds.size() * repetitions) {
            return Decision.UNRESOLVED;
        }
        Set<String> seen = new HashSet<>();
        boolean qualityFailure = false;
        for (Map<String, Object> row : rows) {
            Object attempt = row.get("attempt");
            if (!caseIds.contains(row.get("caseId")) || !(attempt instanceof Integer index)
                    || index < 1 || index > repetitions
                    || !seen.add(row.get("caseId") + ":" + index)
                    || !"MEASURED".equals(row.get("status"))
                    || !Boolean.TRUE.equals(row.get("retrievalComplete"))) {
                return Decision.UNRESOLVED;
            }
            Object verdict = row.get("verdict");
            if (!Set.of("COMPLETE", "PARTIAL", "ABSTAINED", "INVALID").contains(verdict == null ? "" : verdict)
                    || !(row.get("providerInsufficientEvidence") instanceof Boolean)) {
                return Decision.UNRESOLVED;
            }
            qualityFailure |= !"COMPLETE".equals(verdict)
                    || Boolean.TRUE.equals(row.get("providerInsufficientEvidence"));
        }
        return qualityFailure ? Decision.ANSWER_FOLLOW_UP : Decision.NO_CHANGE;
    }

    static Map<String, Object> requestStamp(AnswerRequest request, String policyVersion) {
        return Map.of(
                "contextPolicyVersion", policyVersion,
                "maxOutputCodePoints", request.options().maxOutputCodePoints(),
                "contextDigest", EvaluationTrustContract.fingerprint(List.of(
                        AnswerContextSerializer.serialize(request.question(), request.context()))),
                "promptDigest", GroundedAnswerPromptContract.renderPrompt(request).contentHash());
    }

    /** 僅投影傳輸實際使用的生成參數，不序列化整個設定物件。 */
    static Map<String, Object> providerStamp(OpenAiCompatibleAnswerProperties properties) {
        return Map.of("max_tokens", properties.getMaxOutputTokens(),
                "temperature", properties.getTemperature());
    }

    private LiveProviderMeasurementReport() { }
}
