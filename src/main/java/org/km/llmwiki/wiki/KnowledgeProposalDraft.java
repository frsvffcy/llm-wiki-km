package org.km.llmwiki.wiki;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.km.llmwiki.ai.LlmProposalAction;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validated input for a Proposal. It never causes a Wiki or filesystem write. */
public record KnowledgeProposalDraft(long workspaceId, long documentAnalysisId, long documentId,
                                     long knowledgeCandidateId, LlmProposalAction action,
                                     String mergeTargetReference, String provider, String model,
                                     String promptIdentifier, String promptVersion, String contractVersion,
                                     String validatedPayloadJson, String normalizedDataJson,
                                     List<Long> evidenceSourceChunkIds) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> SECRET_FIELD_NAMES = Set.of("authorization", "password", "secret", "token");

    public KnowledgeProposalDraft {
        positive(workspaceId, "workspaceId");
        positive(documentAnalysisId, "documentAnalysisId");
        positive(documentId, "documentId");
        positive(knowledgeCandidateId, "knowledgeCandidateId");
        action = Objects.requireNonNull(action, "action must not be null");
        mergeTargetReference = optional(mergeTargetReference, "mergeTargetReference");
        provider = required(provider, "provider");
        model = required(model, "model");
        promptIdentifier = required(promptIdentifier, "promptIdentifier");
        promptVersion = required(promptVersion, "promptVersion");
        contractVersion = required(contractVersion, "contractVersion");
        validatedPayloadJson = optionalJson(validatedPayloadJson, "validatedPayloadJson");
        normalizedDataJson = requiredJson(normalizedDataJson, "normalizedDataJson");
        evidenceSourceChunkIds = immutableEvidence(evidenceSourceChunkIds);
    }

    private static void positive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static String required(String value, String field) {
        value = Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String optional(String value, String field) {
        return value == null ? null : required(value, field);
    }

    private static String optionalJson(String value, String field) {
        return value == null ? null : validateJson(value, field);
    }

    private static String requiredJson(String value, String field) {
        return validateJson(required(value, field), field);
    }

    private static String validateJson(String value, String field) {
        try {
            JsonNode json = OBJECT_MAPPER.readTree(value);
            if (json == null || !json.isObject()) {
                throw new IllegalArgumentException(field + " must be a JSON object");
            }
            rejectSecrets(json, field);
            return value;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(field + " must be valid JSON", exception);
        }
    }

    private static void rejectSecrets(JsonNode json, String field) {
        json.properties().forEach(entry -> {
            String normalizedFieldName = entry.getKey().toLowerCase().replace("_", "").replace("-", "");
            if (SECRET_FIELD_NAMES.contains(normalizedFieldName) || normalizedFieldName.contains("apikey")) {
                throw new IllegalArgumentException(field + " must not contain secrets");
            }
            rejectSecrets(entry.getValue(), field);
        });
        json.elements().forEachRemaining(element -> rejectSecrets(element, field));
    }

    private static List<Long> immutableEvidence(List<Long> ids) {
        ids = List.copyOf(Objects.requireNonNull(ids, "evidenceSourceChunkIds must not be null"));
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("evidenceSourceChunkIds must not be empty");
        }
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("evidenceSourceChunkIds must contain only positive ids");
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("evidenceSourceChunkIds must not contain duplicates");
        }
        return ids;
    }
}
