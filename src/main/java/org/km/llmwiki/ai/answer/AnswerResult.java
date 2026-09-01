package org.km.llmwiki.ai.answer;

import java.util.Objects;
import java.util.List;
import java.util.Optional;

/** Immutable generated answer and non-secret provider metadata. */
public record AnswerResult(
        String answerText,
        List<String> citedEvidenceIds,
        boolean insufficientEvidence,
        AnswerProviderMetadata providerMetadata,
        Optional<AnswerUsageMetadata> usage
) {

    private static final int MAX_ANSWER_CHARACTERS = 100_000;

    public AnswerResult {
        if (answerText == null || answerText.isBlank()) {
            throw new IllegalArgumentException("answerText must not be blank");
        }
        if (answerText.length() > MAX_ANSWER_CHARACTERS) {
            throw new IllegalArgumentException("answerText exceeds the bounded result size");
        }
        if (citedEvidenceIds == null || citedEvidenceIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("citedEvidenceIds must not be null");
        }
        if (citedEvidenceIds.stream().anyMatch(id -> id.isBlank() || id.length() > 32)) {
            throw new IllegalArgumentException("citedEvidenceIds contains an invalid id");
        }
        if (insufficientEvidence && !citedEvidenceIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "insufficientEvidence results must not cite evidence");
        }
        citedEvidenceIds = List.copyOf(citedEvidenceIds);
        providerMetadata = Objects.requireNonNull(providerMetadata, "providerMetadata must not be null");
        usage = usage == null ? Optional.empty() : usage;
    }

    public AnswerResult(String answerText, AnswerProviderMetadata providerMetadata) {
        this(answerText, List.of(), false, providerMetadata, Optional.empty());
    }

    /** Compatibility constructor for the STORY-601 provider-neutral result boundary. */
    public AnswerResult(String answerText, AnswerProviderMetadata providerMetadata,
                        Optional<AnswerUsageMetadata> usage) {
        this(answerText, List.of(), false, providerMetadata, usage);
    }
}
