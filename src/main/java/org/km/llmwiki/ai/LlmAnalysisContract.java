package org.km.llmwiki.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Parses the versioned JSON contract returned by any LLM provider implementation. */
public final class LlmAnalysisContract {

    private final ObjectMapper objectMapper;

    public LlmAnalysisContract(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LlmAnalysisResult parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new LlmAnalysisValidationException("LLM result must be a non-empty JSON object");
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || !root.isObject()) {
                throw invalid("LLM result must be a JSON object");
            }
            JsonNode metadata = object(root, "metadata");
            JsonNode analysis = object(root, "analysis");
            return new LlmAnalysisResult(
                    new LlmProviderMetadata(text(metadata, "provider"), text(metadata, "model"),
                            text(metadata, "contractVersion")),
                    action(text(analysis, "action")),
                    text(analysis, "summary"),
                    evidence(analysis),
                    candidates(analysis));
        } catch (JsonProcessingException exception) {
            throw new LlmAnalysisValidationException(LlmFailureType.MALFORMED_JSON,
                    "LLM result is not valid JSON", exception);
        } catch (IllegalArgumentException exception) {
            throw new LlmAnalysisValidationException("LLM result violates the analysis contract: "
                    + exception.getMessage(), exception);
        }
    }

    private static JsonNode object(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw invalid(field + " must be an object");
        }
        return value;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid(field + " must be a non-blank string");
        }
        return value.asText();
    }

    private static LlmProposalAction action(String value) {
        try {
            return LlmProposalAction.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(LlmFailureType.UNKNOWN_ENUM, "action is not supported: " + value);
        }
    }

    private static List<AnalysisEvidence> evidence(JsonNode analysis) {
        JsonNode values = analysis.get("evidence");
        if (values == null || !values.isArray() || values.isEmpty()) {
            throw invalid("evidence must be a non-empty array");
        }
        List<AnalysisEvidence> evidence = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isObject() || !value.has("sourceChunkId") || !value.get("sourceChunkId").canConvertToLong()) {
                throw invalid("evidence.sourceChunkId must be a positive integer");
            }
            evidence.add(new AnalysisEvidence(value.get("sourceChunkId").asLong(), text(value, "quote")));
        }
        return List.copyOf(evidence);
    }

    private static List<KnowledgeCandidate> candidates(JsonNode analysis) {
        JsonNode values = analysis.get("candidates");
        if (values == null) {
            return List.of();
        }
        if (!values.isArray()) {
            throw invalid("candidates must be an array");
        }
        List<KnowledgeCandidate> candidates = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isObject()) {
                throw invalid("candidate must be an object");
            }
            candidates.add(new KnowledgeCandidate(text(value, "title"), candidateType(text(value, "type")),
                    text(value, "summary"), sourceChunkIds(value), confidence(value), text(value, "rationale")));
        }
        return List.copyOf(candidates);
    }

    private static KnowledgeCandidateType candidateType(String value) {
        try {
            return KnowledgeCandidateType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(LlmFailureType.UNKNOWN_ENUM, "candidate type is not supported: " + value);
        }
    }

    private static List<Long> sourceChunkIds(JsonNode candidate) {
        JsonNode values = candidate.get("evidenceSourceChunkIds");
        if (values == null || !values.isArray() || values.isEmpty()) {
            throw invalid("candidate.evidenceSourceChunkIds must be a non-empty array");
        }
        List<Long> ids = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.canConvertToLong()) {
                throw invalid("candidate.evidenceSourceChunkIds must contain positive integers");
            }
            ids.add(value.asLong());
        }
        return List.copyOf(ids);
    }

    private static double confidence(JsonNode candidate) {
        JsonNode value = candidate.get("confidence");
        if (value == null || !value.isNumber()) {
            throw invalid("candidate.confidence must be a number between 0 and 1");
        }
        return value.asDouble();
    }

    private static LlmAnalysisValidationException invalid(String message) {
        return new LlmAnalysisValidationException(message);
    }

    private static LlmAnalysisValidationException invalid(LlmFailureType failureType, String message) {
        return new LlmAnalysisValidationException(failureType, message);
    }
}
