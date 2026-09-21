package org.km.llmwiki.ai.ask;

import com.fasterxml.jackson.databind.JsonNode;
import org.km.llmwiki.rag.RetrievalMode;

import java.util.Set;

/** Public request boundary for one stateless Ask operation. */
public record AskApiRequest(String question, RetrievalMode retrievalMode, Long documentId) {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "question", "retrievalMode", "documentId");

    public AskApiRequest(String question, RetrievalMode retrievalMode) {
        this(question, retrievalMode, null);
    }

    static AskApiRequest fromJson(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("Ask request must be a JSON object");
        }
        body.fieldNames().forEachRemaining(field -> {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new IllegalArgumentException("unsupported Ask request field");
            }
        });
        JsonNode question = body.get("question");
        JsonNode retrievalMode = body.get("retrievalMode");
        if (question == null || !question.isTextual()) {
            throw new IllegalArgumentException("question must be a string");
        }
        if (retrievalMode == null || !retrievalMode.isTextual()) {
            throw new IllegalArgumentException("retrievalMode must be a string");
        }
        final RetrievalMode mode;
        try {
            mode = RetrievalMode.valueOf(retrievalMode.textValue());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("retrievalMode is invalid");
        }
        JsonNode documentId = body.get("documentId");
        Long scopedDocumentId = null;
        if (documentId != null) {
            if (!documentId.isIntegralNumber() || !documentId.canConvertToLong()
                    || documentId.longValue() <= 0) {
                throw new IllegalArgumentException("documentId must be a positive integer");
            }
            scopedDocumentId = documentId.longValue();
        }
        return new AskApiRequest(question.textValue(), mode, scopedDocumentId);
    }

    public AskApiRequest {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        question = question.strip();
        if (question.codePointCount(0, question.length()) > 4_000) {
            throw new IllegalArgumentException("question must not exceed 4000 Unicode code points");
        }
        if (retrievalMode == null) {
            throw new IllegalArgumentException("retrievalMode is required");
        }
        if (documentId != null && documentId <= 0) {
            throw new IllegalArgumentException("documentId must be positive");
        }
    }

    AskRequest toApplicationRequest() {
        return AskRequest.defaults(question, retrievalMode, documentId);
    }
}
