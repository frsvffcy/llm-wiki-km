package org.km.llmwiki.ai.answer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Provider-neutral structured response before or after citation validation. */
public record GroundedAnswerResponse(
        String answerText,
        List<String> citedEvidenceIds,
        boolean insufficientEvidence,
        AnswerProviderMetadata providerMetadata,
        Optional<AnswerUsageMetadata> usage
) {

    public static final int MAX_ANSWER_CODE_POINTS = AnswerGenerationOptions.MAX_ALLOWED_OUTPUT_CODE_POINTS;
    public static final int MAX_CITATION_ID_LENGTH = 32;

    public GroundedAnswerResponse {
        if (answerText == null || answerText.isBlank()) {
            throw new IllegalArgumentException("answerText must not be blank");
        }
        if (answerText.codePointCount(0, answerText.length()) > MAX_ANSWER_CODE_POINTS) {
            throw new IllegalArgumentException("answerText exceeds the bounded result size");
        }
        if (citedEvidenceIds == null || citedEvidenceIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("citedEvidenceIds must not be null");
        }
        if (citedEvidenceIds.stream().anyMatch(id -> id.isBlank() || id.length() > MAX_CITATION_ID_LENGTH)) {
            throw new IllegalArgumentException("citedEvidenceIds contains an invalid id");
        }
        citedEvidenceIds = List.copyOf(citedEvidenceIds);
        providerMetadata = Objects.requireNonNull(providerMetadata,
                "providerMetadata must not be null");
        usage = usage == null ? Optional.empty() : usage;
    }

    public GroundedAnswerResponse(String answerText, List<String> citedEvidenceIds,
                                  boolean insufficientEvidence, AnswerProviderMetadata providerMetadata) {
        this(answerText, citedEvidenceIds, insufficientEvidence, providerMetadata, Optional.empty());
    }

    /** Returns a response with application-normalized citation order and duplicates removed. */
    public GroundedAnswerResponse withCitations(List<String> normalizedCitationIds) {
        return new GroundedAnswerResponse(answerText, normalizedCitationIds, insufficientEvidence,
                providerMetadata, usage);
    }
}
