package org.km.llmwiki.ai.answer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class AnswerFailureTest {

    @ParameterizedTest
    @EnumSource(AnswerFailureType.class)
    void preservesEveryStableFailureCategoryWithoutReturningAnAnswer(AnswerFailureType type) {
        StubAnswerClient client = StubAnswerClient.failing(type, "bounded provider diagnostic");

        assertThatThrownBy(() -> client.generate(request()))
                .isInstanceOf(AnswerClientException.class)
                .satisfies(thrown -> {
                    AnswerClientException exception = (AnswerClientException) thrown;
                    assertThat(exception.failureType()).isEqualTo(type);
                    assertThat(exception.publicCode()).isEqualTo(type.publicCode());
                    assertThat(exception.failure().retryable()).isEqualTo(type.retryable());
                });
    }

    @Test
    void redactsSecretsAndBoundsDiagnosticDetails() {
        String secret = "sk-live-1234567890abcdef";
        AnswerFailure failure = new AnswerFailure(AnswerFailureType.AUTHENTICATION_OR_AUTHORIZATION,
                "authorization: Bearer " + secret + " " + "document-content-".repeat(30));
        AnswerClientException exception = new AnswerClientException(failure);

        assertThat(failure.diagnostic()).hasSizeLessThanOrEqualTo(AnswerFailure.MAX_DIAGNOSTIC_LENGTH);
        assertThat(failure.diagnostic()).doesNotContain(secret);
        assertThat(exception.getMessage()).doesNotContain(secret);
        assertThat(failure.diagnostic()).contains("[REDACTED]");
    }

    @Test
    void nullRequestIsAStableLocalValidationFailure() {
        assertThatThrownBy(() -> StubAnswerClient.failing(
                AnswerFailureType.PROVIDER_SERVER_FAILURE, "server").generate(null))
                .isInstanceOf(AnswerClientException.class)
                .extracting(exception -> ((AnswerClientException) exception).failureType())
                .isEqualTo(AnswerFailureType.LOCAL_VALIDATION);
    }

    private static AnswerRequest request() {
        return new AnswerRequest("question", AnswerContext.empty(), AnswerGenerationOptions.defaults());
    }
}
