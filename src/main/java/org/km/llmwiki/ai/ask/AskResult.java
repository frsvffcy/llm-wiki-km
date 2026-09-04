package org.km.llmwiki.ai.ask;

import org.km.llmwiki.ai.answer.AnswerProviderMetadata;
import org.km.llmwiki.ai.answer.AnswerUsageMetadata;
import org.km.llmwiki.rag.RetrievalDiagnostics;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Provider-neutral result of one bounded Ask operation. */
public record AskResult(
        AskStatus status,
        Optional<String> answerText,
        List<AskCitation> citations,
        List<AskCitation> suppliedEvidence,
        Optional<AnswerProviderMetadata> providerMetadata,
        Optional<AnswerUsageMetadata> usage,
        Optional<AskFailure> failure,
        AskExecutionMetadata executionMetadata,
        RetrievalDiagnostics retrievalDiagnostics
) {

    public AskResult(AskStatus status, Optional<String> answerText, List<AskCitation> citations,
                     List<AskCitation> suppliedEvidence,
                     Optional<AnswerProviderMetadata> providerMetadata,
                     Optional<AnswerUsageMetadata> usage, Optional<AskFailure> failure,
                     AskExecutionMetadata executionMetadata) {
        this(status, answerText, citations, suppliedEvidence, providerMetadata, usage, failure,
                executionMetadata, RetrievalDiagnostics.lexical());
    }

    public AskResult {
        status = Objects.requireNonNull(status, "status must not be null");
        answerText = answerText == null ? Optional.empty() : answerText;
        if (answerText.isPresent() && answerText.get().isBlank()) {
            throw new IllegalArgumentException("answerText must not be blank");
        }
        citations = immutableList(citations, "citations");
        suppliedEvidence = immutableList(suppliedEvidence, "suppliedEvidence");
        providerMetadata = providerMetadata == null ? Optional.empty() : providerMetadata;
        usage = usage == null ? Optional.empty() : usage;
        failure = failure == null ? Optional.empty() : failure;
        executionMetadata = Objects.requireNonNull(executionMetadata,
                "executionMetadata must not be null");
        retrievalDiagnostics = Objects.requireNonNull(retrievalDiagnostics,
                "retrievalDiagnostics must not be null");
        if (status == AskStatus.ANSWERED
                && (answerText.isEmpty() || citations.isEmpty() || failure.isPresent())) {
            throw new IllegalArgumentException(
                    "answered results require an answer, at least one citation, and no failure");
        }
        if (status == AskStatus.INSUFFICIENT_EVIDENCE
                && (answerText.isPresent() || !citations.isEmpty() || failure.isPresent())) {
            throw new IllegalArgumentException(
                    "insufficient evidence results must not contain an answer or failure");
        }
        if (status == AskStatus.FAILED && failure.isEmpty()) {
            throw new IllegalArgumentException("failed results require a typed failure");
        }
    }

    public boolean insufficientEvidence() {
        return status == AskStatus.INSUFFICIENT_EVIDENCE;
    }

    public boolean successful() {
        return status == AskStatus.ANSWERED;
    }

    public Optional<String> answer() {
        return answerText;
    }

    private static <T> List<T> immutableList(List<T> values, String field) {
        if (values == null || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + " must not contain null");
        }
        return List.copyOf(values);
    }
}
