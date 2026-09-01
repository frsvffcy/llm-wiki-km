package org.km.llmwiki.ai.answer;

import java.util.List;
import java.util.Objects;

/**
 * Bounded references to authoritative context. The reference list is deliberately not a prompt
 * or a copy of document content; a later context assembly layer owns that representation.
 */
public record AnswerContext(List<AnswerContextReference> references) {

    public static final int MAX_REFERENCES = 64;

    public AnswerContext {
        if (references == null) {
            throw new IllegalArgumentException("context references must not be null");
        }
        if (references.size() > MAX_REFERENCES) {
            throw new IllegalArgumentException("context references must not exceed " + MAX_REFERENCES);
        }
        if (references.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("context references must not contain null");
        }
        references = List.copyOf(references);
        if (references.stream().distinct().count() != references.size()) {
            throw new IllegalArgumentException("context references must be unique");
        }
    }

    public static AnswerContext empty() {
        return new AnswerContext(List.of());
    }
}
