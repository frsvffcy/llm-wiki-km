package org.km.llmwiki.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmAnalysisContractTest {

    private final LlmAnalysisContract contract = new LlmAnalysisContract(new ObjectMapper());

    @Test
    void parsesACompleteStructuredResultFromTheOfflineProvider() {
        StubLlmClient client = new StubLlmClient(contract, request -> """
                {
                  "metadata": {"provider": "stub", "model": "test-model", "contractVersion": "v1"},
                  "analysis": {"action": "CREATE", "summary": "建立知識候選項目", "evidence": [
                    {"sourceChunkId": 41, "quote": "可追溯的內容"}
                  ]}
                }
                """);

        LlmAnalysisResult result = client.analyze(request());

        assertThat(result.action()).isEqualTo(LlmProposalAction.CREATE);
        assertThat(result.metadata()).isEqualTo(new LlmProviderMetadata("stub", "test-model", "v1"));
        assertThat(result.evidence()).containsExactly(new AnalysisEvidence(41, "可追溯的內容"));
    }

    @Test
    void rejectsAnUnknownAction() {
        assertThatThrownBy(() -> contract.parse(payload("PUBLISH", "[{\"sourceChunkId\": 41, \"quote\": \"內容\"}]")))
                .isInstanceOf(LlmAnalysisValidationException.class)
                .hasMessageContaining("action is not supported: PUBLISH");
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
                .hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> contract.parse("{\"metadata\": {}, \"analysis\": {}}"))
                .isInstanceOf(LlmAnalysisValidationException.class)
                .hasMessageContaining("provider must be a non-blank string");
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
}
