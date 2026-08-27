package org.km.llmwiki.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Provides an offline, replaceable default provider until a real provider is configured. */
@Configuration
public class StubLlmClientConfiguration {

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    LlmClient stubLlmClient(ObjectMapper objectMapper) {
        LlmAnalysisContract contract = new LlmAnalysisContract(objectMapper);
        return new StubLlmClient(contract, request -> response(objectMapper, request));
    }

    private static String response(ObjectMapper objectMapper, DocumentAnalysisRequest request) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("provider", request.settings() == null ? AnalysisSettings.DEFAULT_PROVIDER : request.settings().provider());
            metadata.put("model", request.settings() == null ? AnalysisSettings.DEFAULT_MODEL : request.settings().model());
            metadata.put("contractVersion", "v1");
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("sourceChunkId", request.sourceChunkEvidence().getFirst().sourceChunkId());
            evidence.put("quote", request.sourceChunkEvidence().getFirst().content());
            Map<String, Object> analysis = new LinkedHashMap<>();
            analysis.put("action", "REVIEW");
            analysis.put("summary", "離線分析結果，待人工審閱");
            analysis.put("evidence", List.of(evidence));
            return objectMapper.writeValueAsString(Map.of("metadata", metadata, "analysis", analysis));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not create offline analysis response", exception);
        }
    }
}
