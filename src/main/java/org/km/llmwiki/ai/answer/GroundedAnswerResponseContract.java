package org.km.llmwiki.ai.answer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

/** Parses and validates the provider-neutral grounded answer response contract. */
public final class GroundedAnswerResponseContract {

    public static final int MAX_PROVIDER_RESPONSE_CODE_POINTS = 120_000;
    private static final Set<String> RESPONSE_FIELDS = Set.of(
            "answerText", "citedEvidenceIds", "insufficientEvidence", "metadata", "usage");
    private static final Set<String> METADATA_FIELDS = Set.of("provider", "model");
    private static final Set<String> USAGE_FIELDS = Set.of("inputTokens", "outputTokens", "totalTokens");

    private final ObjectMapper objectMapper;

    public GroundedAnswerResponseContract(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Parses structure and scalar bounds without silently accepting provider citations. */
    public GroundedAnswerResponse parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw invalid(GroundedAnswerValidationErrorCode.MALFORMED_JSON,
                    "provider response is empty");
        }
        if (payload.codePointCount(0, payload.length()) > MAX_PROVIDER_RESPONSE_CODE_POINTS) {
            throw invalid(GroundedAnswerValidationErrorCode.RESPONSE_TOO_LARGE,
                    "provider response exceeds the bounded size");
        }
        try {
            JsonNode root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(payload);
            if (root == null) {
                throw invalid(GroundedAnswerValidationErrorCode.MALFORMED_JSON,
                        "provider response is empty");
            }
            if (!root.isObject()) {
                throw invalid(GroundedAnswerValidationErrorCode.RESPONSE_NOT_OBJECT,
                        "provider response must be a JSON object");
            }
            rejectUnknownFields(root, RESPONSE_FIELDS, "response");
            String answerText = requiredText(root, "answerText",
                    GroundedAnswerValidationErrorCode.ANSWER_TEXT_INVALID);
            if (answerText.codePointCount(0, answerText.length())
                    > GroundedAnswerResponse.MAX_ANSWER_CODE_POINTS) {
                throw invalid(GroundedAnswerValidationErrorCode.ANSWER_TEXT_INVALID,
                        "answerText exceeds the bounded result size");
            }
            List<String> citations = citationIds(root);
            boolean insufficient = requiredBoolean(root, "insufficientEvidence");
            if (insufficient && !citations.isEmpty()) {
                throw invalid(GroundedAnswerValidationErrorCode.INSUFFICIENT_EVIDENCE_CONFLICT,
                        "insufficientEvidence responses must not cite evidence");
            }
            AnswerProviderMetadata metadata = metadata(root);
            Optional<AnswerUsageMetadata> usage = usage(root);
            return new GroundedAnswerResponse(answerText, citations, insufficient, metadata, usage);
        } catch (JsonProcessingException exception) {
            throw invalid(GroundedAnswerValidationErrorCode.MALFORMED_JSON,
                    "provider response is not valid JSON");
        } catch (GroundedAnswerValidationException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw invalid(GroundedAnswerValidationErrorCode.FIELD_TYPE_INVALID,
                    "provider response violates the structured response contract");
        }
    }

    /** Parses and then validates every citation against the application-owned supplied context. */
    public GroundedAnswerResponse parse(String payload, AnswerContext context) {
        return validate(parse(payload), context);
    }

    public GroundedAnswerResponse validate(GroundedAnswerResponse response, AnswerContext context) {
        if (response == null) {
            throw invalid(GroundedAnswerValidationErrorCode.REQUIRED_FIELD_MISSING,
                    "provider response must not be null");
        }
        if (context == null) {
            throw invalid(GroundedAnswerValidationErrorCode.FIELD_TYPE_INVALID,
                    "answer context must not be null");
        }
        if (response.insufficientEvidence() && !response.citedEvidenceIds().isEmpty()) {
            throw invalid(GroundedAnswerValidationErrorCode.INSUFFICIENT_EVIDENCE_CONFLICT,
                    "insufficientEvidence responses must not cite evidence");
        }
        if (context.blocks().isEmpty() && !response.insufficientEvidence()) {
            throw invalid(GroundedAnswerValidationErrorCode.INSUFFICIENT_EVIDENCE_CONFLICT,
                    "empty evidence context requires insufficientEvidence");
        }
        try {
            return response.withCitations(context.normalizeCitationIds(response.citedEvidenceIds()));
        } catch (CitationValidationException exception) {
            throw invalid(GroundedAnswerValidationErrorCode.UNKNOWN_CITATION_ID,
                    "provider response contains an unknown or malformed citation id");
        }
    }

    private static List<String> citationIds(JsonNode root) {
        JsonNode values = required(root, "citedEvidenceIds");
        if (!values.isArray()) {
            throw invalid(GroundedAnswerValidationErrorCode.CITATION_INVALID,
                    "citedEvidenceIds must be an array");
        }
        List<String> citations = StreamSupport.stream(values.spliterator(), false)
                .map(value -> {
                    if (!value.isTextual() || value.asText().isBlank()
                            || value.asText().length() > GroundedAnswerResponse.MAX_CITATION_ID_LENGTH) {
                        throw invalid(GroundedAnswerValidationErrorCode.CITATION_INVALID,
                                "citedEvidenceIds must contain bounded strings");
                    }
                    return value.asText();
                })
                .toList();
        return citations;
    }

    private static AnswerProviderMetadata metadata(JsonNode root) {
        JsonNode value = required(root, "metadata");
        if (!value.isObject()) {
            throw invalid(GroundedAnswerValidationErrorCode.METADATA_INVALID,
                    "metadata must be an object");
        }
        rejectUnknownFields(value, METADATA_FIELDS, "metadata");
        try {
            return new AnswerProviderMetadata(requiredText(value, "provider",
                    GroundedAnswerValidationErrorCode.METADATA_INVALID),
                    requiredText(value, "model", GroundedAnswerValidationErrorCode.METADATA_INVALID));
        } catch (IllegalArgumentException exception) {
            throw invalid(GroundedAnswerValidationErrorCode.METADATA_INVALID,
                    "metadata fields are invalid");
        }
    }

    private static Optional<AnswerUsageMetadata> usage(JsonNode root) {
        JsonNode value = root.get("usage");
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isObject()) {
            throw invalid(GroundedAnswerValidationErrorCode.USAGE_INVALID,
                    "usage must be an object");
        }
        rejectUnknownFields(value, USAGE_FIELDS, "usage");
        Integer input = optionalNonNegativeInt(value, "inputTokens");
        Integer output = optionalNonNegativeInt(value, "outputTokens");
        Integer total = optionalNonNegativeInt(value, "totalTokens");
        try {
            return Optional.of(new AnswerUsageMetadata(input, output, total));
        } catch (IllegalArgumentException exception) {
            throw invalid(GroundedAnswerValidationErrorCode.USAGE_INVALID,
                    "usage must contain at least one non-negative counter");
        }
    }

    private static Integer optionalNonNegativeInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0) {
            throw invalid(GroundedAnswerValidationErrorCode.USAGE_INVALID,
                    "usage counters must be non-negative integers");
        }
        return value.asInt();
    }

    private static String requiredText(JsonNode parent, String field,
                                       GroundedAnswerValidationErrorCode errorCode) {
        JsonNode value = required(parent, field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalid(errorCode, field + " must be a non-blank string");
        }
        return value.asText();
    }

    private static boolean requiredBoolean(JsonNode parent, String field) {
        JsonNode value = required(parent, field);
        if (!value.isBoolean()) {
            throw invalid(GroundedAnswerValidationErrorCode.FIELD_TYPE_INVALID,
                    field + " must be a boolean");
        }
        return value.booleanValue();
    }

    private static JsonNode required(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            throw invalid(GroundedAnswerValidationErrorCode.REQUIRED_FIELD_MISSING,
                    field + " is required");
        }
        return value;
    }

    private static void rejectUnknownFields(JsonNode object, Set<String> allowed, String objectName) {
        Set<String> unknown = new HashSet<>();
        Iterator<String> fields = object.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                unknown.add(field);
            }
        }
        if (!unknown.isEmpty()) {
            throw invalid(GroundedAnswerValidationErrorCode.FIELD_TYPE_INVALID,
                    objectName + " contains unsupported fields");
        }
    }

    private static GroundedAnswerValidationException invalid(
            GroundedAnswerValidationErrorCode errorCode, String diagnostic) {
        return new GroundedAnswerValidationException(errorCode, diagnostic);
    }
}
