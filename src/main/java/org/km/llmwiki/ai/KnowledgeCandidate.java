package org.km.llmwiki.ai;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * A provider-neutral, reviewable knowledge candidate. It is not a Wiki page or a publish command.
 */
public record KnowledgeCandidate(String title, KnowledgeCandidateType type, String summary,
                                 List<Long> evidenceSourceChunkIds, double confidence, String rationale) {

    public KnowledgeCandidate {
        title = required(title, "title");
        type = Objects.requireNonNull(type, "type must not be null");
        summary = required(summary, "summary");
        evidenceSourceChunkIds = List.copyOf(Objects.requireNonNull(evidenceSourceChunkIds,
                "evidenceSourceChunkIds must not be null"));
        if (evidenceSourceChunkIds.isEmpty()) {
            throw new IllegalArgumentException("evidenceSourceChunkIds must not be empty");
        }
        if (evidenceSourceChunkIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("evidenceSourceChunkIds must contain only positive ids");
        }
        if (new HashSet<>(evidenceSourceChunkIds).size() != evidenceSourceChunkIds.size()) {
            throw new IllegalArgumentException("evidenceSourceChunkIds must not contain duplicates");
        }
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        rationale = required(rationale, "rationale");
    }

    private static String required(String value, String field) {
        value = Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
