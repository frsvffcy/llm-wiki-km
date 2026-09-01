package org.km.llmwiki.ai.answer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("contract")
class GroundedAnswerResponseContractTest {

    private final GroundedAnswerResponseContract contract =
            new GroundedAnswerResponseContract(new ObjectMapper());

    @Test
    void parsesStructuredResponseAndNormalizesKnownCitationsAgainstContext() {
        GroundedAnswerResponse response = contract.parse("""
                {
                  "answerText": "Local-first means the vault remains authoritative.",
                  "citedEvidenceIds": ["E2", "E1", "E2"],
                  "insufficientEvidence": false,
                  "metadata": {"provider": "stub", "model": "offline-model"},
                  "usage": {"inputTokens": 20, "outputTokens": 12, "totalTokens": 32}
                }
                """, context(2));

        assertThat(response.answerText()).contains("authoritative");
        assertThat(response.citedEvidenceIds()).containsExactly("E2", "E1");
        assertThat(response.insufficientEvidence()).isFalse();
        assertThat(response.providerMetadata())
                .isEqualTo(new AnswerProviderMetadata("stub", "offline-model"));
        assertThat(response.usage()).contains(new AnswerUsageMetadata(20, 12, 32));
    }

    @Test
    void rejectsMalformedJsonMissingFieldsAndWrongTypesWithoutRawPayload() {
        assertThatThrownBy(() -> contract.parse("{not json"))
                .isInstanceOf(GroundedAnswerValidationException.class)
                .extracting(error -> ((GroundedAnswerValidationException) error).errorCode())
                .isEqualTo(GroundedAnswerValidationErrorCode.MALFORMED_JSON);
        assertThatThrownBy(() -> contract.parse(
                "{\"answerText\":\"answer\",\"citedEvidenceIds\":[],"
                        + "\"insufficientEvidence\":true,\"metadata\":{\"provider\":\"p\",\"model\":\"m\"}} trailing"))
                .isInstanceOf(GroundedAnswerValidationException.class)
                .extracting(error -> ((GroundedAnswerValidationException) error).errorCode())
                .isEqualTo(GroundedAnswerValidationErrorCode.MALFORMED_JSON);
        assertThatThrownBy(() -> contract.parse("{\"answerText\":\"answer\"}"))
                .isInstanceOf(GroundedAnswerValidationException.class)
                .extracting(error -> ((GroundedAnswerValidationException) error).errorCode())
                .isEqualTo(GroundedAnswerValidationErrorCode.REQUIRED_FIELD_MISSING);
        assertThatThrownBy(() -> contract.parse("""
                {"answerText": "answer", "citedEvidenceIds": {},
                 "insufficientEvidence": false, "metadata": {"provider":"p","model":"m"}}
                """))
                .isInstanceOf(GroundedAnswerValidationException.class)
                .extracting(error -> ((GroundedAnswerValidationException) error).errorCode())
                .isEqualTo(GroundedAnswerValidationErrorCode.CITATION_INVALID);
    }

    @Test
    void rejectsUnknownCitationsAndIllegalInsufficientEvidenceCombinations() {
        assertThatThrownBy(() -> contract.parse(payload("E9", false), context(1)))
                .isInstanceOf(GroundedAnswerValidationException.class)
                .satisfies(error -> assertThat(((GroundedAnswerValidationException) error).errorCode())
                        .isEqualTo(GroundedAnswerValidationErrorCode.UNKNOWN_CITATION_ID));
        assertThatThrownBy(() -> contract.parse(payload("E1", true), context(1)))
                .isInstanceOf(GroundedAnswerValidationException.class)
                .satisfies(error -> assertThat(((GroundedAnswerValidationException) error).errorCode())
                        .isEqualTo(GroundedAnswerValidationErrorCode.INSUFFICIENT_EVIDENCE_CONFLICT));
        GroundedAnswerResponse insufficient = contract.parse(
                """
                {"answerText":"There is not enough evidence.","citedEvidenceIds":[],
                "insufficientEvidence":true,"metadata":{"provider":"stub","model":"m"}}
                """,
                context(1));
        assertThat(insufficient.insufficientEvidence()).isTrue();
        assertThat(insufficient.citedEvidenceIds()).isEmpty();
    }

    @Test
    void rejectsNonInsufficientResponseWithoutCitations() {
        assertThatThrownBy(() -> contract.parse(
                payloadWithCitations(List.of(), false), context(1)))
                .isInstanceOf(GroundedAnswerValidationException.class)
                .extracting(error -> ((GroundedAnswerValidationException) error).errorCode())
                .isEqualTo(GroundedAnswerValidationErrorCode.CITATION_INVALID);
    }

    @Test
    void requiresInsufficientEvidenceWhenTheSuppliedContextIsEmpty() {
        assertThatThrownBy(() -> contract.parse(
                payloadWithCitations(List.of(), false), AnswerContext.empty()))
                .isInstanceOf(GroundedAnswerValidationException.class)
                .satisfies(error -> assertThat(((GroundedAnswerValidationException) error).errorCode())
                        .isEqualTo(GroundedAnswerValidationErrorCode.INSUFFICIENT_EVIDENCE_CONFLICT));
    }

    @Test
    void boundsDiagnosticsAndDoesNotEchoSensitiveOrCompleteProviderData() {
        String secret = "sk-live-1234567890abcdef";
        String payload = "{" + "\"answerText\":\"answer\",\"citedEvidenceIds\":[],"
                + "\"insufficientEvidence\":false,\"metadata\":{\"provider\":\"p\","
                + "\"model\":\"m\"},\"unexpected\":\"authorization: Bearer " + secret
                + " " + "evidence-content-".repeat(40) + "\"}";

        assertThatThrownBy(() -> contract.parse(payload))
                .isInstanceOf(GroundedAnswerValidationException.class)
                .satisfies(error -> {
                    GroundedAnswerValidationException exception =
                            (GroundedAnswerValidationException) error;
                    assertThat(exception.getMessage()).hasSizeLessThanOrEqualTo(
                            GroundedAnswerValidationException.MAX_DIAGNOSTIC_LENGTH + 80);
                    assertThat(exception.getMessage()).doesNotContain(secret);
                    assertThat(exception.getMessage()).doesNotContain("evidence-content-");
                });
        GroundedAnswerValidationException diagnostic = new GroundedAnswerValidationException(
                GroundedAnswerValidationErrorCode.FIELD_TYPE_INVALID,
                "authorization: Bearer " + secret + " evidence-content-".repeat(40));
        assertThat(diagnostic.getMessage()).doesNotContain(secret)
                .hasSizeLessThanOrEqualTo(GroundedAnswerValidationException.MAX_DIAGNOSTIC_LENGTH + 80);
    }

    @Test
    void rejectsOversizedAnswerAndInvalidUsage() {
        String oversized = "a".repeat(GroundedAnswerResponse.MAX_ANSWER_CODE_POINTS + 1);
        assertThatThrownBy(() -> contract.parse("""
                {"answerText":"%s","citedEvidenceIds":[],"insufficientEvidence":true,
                 "metadata":{"provider":"p","model":"m"}}
                """.formatted(oversized)))
                .isInstanceOf(GroundedAnswerValidationException.class)
                .extracting(error -> ((GroundedAnswerValidationException) error).errorCode())
                .isEqualTo(GroundedAnswerValidationErrorCode.ANSWER_TEXT_INVALID);
        assertThatThrownBy(() -> contract.parse("""
                {"answerText":"answer","citedEvidenceIds":[],"insufficientEvidence":true,
                 "metadata":{"provider":"p","model":"m"},"usage":{"totalTokens":-1}}
                """))
                .isInstanceOf(GroundedAnswerValidationException.class)
                .extracting(error -> ((GroundedAnswerValidationException) error).errorCode())
                .isEqualTo(GroundedAnswerValidationErrorCode.USAGE_INVALID);
    }

    private static AnswerContext context(int count) {
        return AnswerContext.fromReferences(java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> new AnswerContextReference("WIKI:page-" + index, "hash-" + index))
                .toList());
    }

    private static String payload(String citationId, boolean insufficient) {
        return payloadWithCitations(List.of(citationId), insufficient);
    }

    private static String payloadWithCitations(List<String> citationIds, boolean insufficient) {
        String citations = citationIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return """
                {"answerText":"answer","citedEvidenceIds":[%s],
                "insufficientEvidence":%s,"metadata":{"provider":"p","model":"m"}}
                """.formatted(citations, insufficient);
    }
}
