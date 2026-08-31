package org.km.llmwiki.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("contract")
class LlmAnalysisContractTest {

    private final LlmAnalysisContract contract = new LlmAnalysisContract(new ObjectMapper());

    @Test
    void parsesACompleteStructuredResultFromTheOfflineProvider() {
        StubLlmClient client = new StubLlmClient(contract, request -> """
                {
                  "metadata": {"provider": "stub", "model": "test-model", "contractVersion": "v1"},
                  "analysis": {"action": "CREATE", "summary": "建立知識候選項目", "evidence": [
                    {"sourceChunkId": 41, "quote": "可追溯的內容"}
                  ], "candidates": [{
                    "title": "可追溯的知識", "type": "CONCEPT", "summary": "候選摘要",
                    "evidenceSourceChunkIds": [41], "confidence": 0.8, "rationale": "有足夠證據"
                  }]}
                }
                """);

        LlmAnalysisResult result = client.analyze(request());

        assertThat(result.action()).isEqualTo(LlmProposalAction.CREATE);
        assertThat(result.metadata()).isEqualTo(new LlmProviderMetadata("stub", "test-model", "v1"));
        assertThat(result.evidence()).containsExactly(new AnalysisEvidence(41, "可追溯的內容"));
        assertThat(result.candidates()).containsExactly(new KnowledgeCandidate("可追溯的知識",
                KnowledgeCandidateType.CONCEPT, "候選摘要", List.of(41L), 0.8, "有足夠證據"));
    }

    @Test
    void rejectsAnUnknownAction() {
        assertThatThrownBy(() -> contract.parse(payload("PUBLISH", "[{\"sourceChunkId\": 41, \"quote\": \"內容\"}]")))
                .isInstanceOf(LlmAnalysisValidationException.class)
                .hasMessageContaining("action is not supported: PUBLISH")
                .extracting(error -> ((LlmAnalysisValidationException) error).errorCode())
                .isEqualTo(AnalysisFailureCode.UNKNOWN_ENUM);
    }

    @Test
    void rejectsAResultWithoutEvidence() {
        assertThatThrownBy(() -> contract.parse(payload("CREATE", "[]")))
                .isInstanceOf(LlmAnalysisValidationException.class)
                .hasMessageContaining("evidence must be a non-empty array");
    }

    @Test
    void rejectsInvalidJsonAndMissingContractFields() {
        assertThatThrownBy(() -> contract.parse("{not json"))
                .isInstanceOf(LlmAnalysisValidationException.class)
                .hasMessageContaining("not valid JSON")
                .extracting(error -> ((LlmAnalysisValidationException) error).errorCode())
                .isEqualTo(AnalysisFailureCode.MALFORMED_JSON);
        assertThatThrownBy(() -> contract.parse("{\"metadata\": {}, \"analysis\": {}}"))
                .isInstanceOf(LlmAnalysisValidationException.class)
                .hasMessageContaining("provider must be a non-blank string")
                .extracting(error -> ((LlmAnalysisValidationException) error).errorCode())
                .isEqualTo(AnalysisFailureCode.CONTRACT_VALIDATION_FAILED);
    }

    @Test
    void rejectsUnknownCandidateTypeAndInvalidConfidence() {
        assertThatThrownBy(() -> contract.parse(payloadWithCandidates("UNKNOWN", "0.8")))
                .isInstanceOf(LlmAnalysisValidationException.class)
                .hasMessageContaining("candidate type is not supported: UNKNOWN");
        assertThatThrownBy(() -> contract.parse(payloadWithCandidates("FACT", "1.1")))
                .isInstanceOf(LlmAnalysisValidationException.class)
                .hasMessageContaining("confidence must be between 0 and 1");
    }

    @Test
    void requiresTraceableSourceChunkEvidenceOnTheRequest() {
        assertThatThrownBy(() -> new DocumentAnalysisRequest(
                new DocumentAnalysisMetadata(1, "notes.md", "text/markdown", "abc123"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceChunkEvidence must not be empty");
    }

    private static DocumentAnalysisRequest request() {
        return new DocumentAnalysisRequest(
                new DocumentAnalysisMetadata(7, "notes.md", "text/markdown", "document-hash"),
                List.of(new SourceChunkEvidence(41, 0, "chunk-hash", "可追溯的內容")));
    }

    private static String payload(String action, String evidence) {
        return """
                {
                  "metadata": {"provider": "stub", "model": "test-model", "contractVersion": "v1"},
                  "analysis": {"action": "%s", "summary": "摘要", "evidence": %s}
                }
                """.formatted(action, evidence);
    }

    private static String payloadWithCandidates(String type, String confidence) {
        return """
                {
                  "metadata": {"provider": "stub", "model": "test-model", "contractVersion": "v1"},
                  "analysis": {"action": "CREATE", "summary": "摘要", "evidence": [
                    {"sourceChunkId": 41, "quote": "內容"}
                  ], "candidates": [{
                    "title": "候選", "type": "%s", "summary": "候選摘要",
                    "evidenceSourceChunkIds": [41], "confidence": %s, "rationale": "理由"
                  }]}
                }
                """.formatted(type, confidence);
    }
}
