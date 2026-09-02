package org.km.llmwiki.ai.embedding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class EmbeddingFailureTest {

    @ParameterizedTest
    @EnumSource(EmbeddingFailureType.class)
    void preservesEveryStableFailureCategory(EmbeddingFailureType type) {
        EmbeddingClient client = request -> {
            throw new EmbeddingClientException(type, "bounded provider diagnostic");
        };

        assertThatThrownBy(() -> client.embed(EmbeddingRequest.single("question")))
                .isInstanceOf(EmbeddingClientException.class)
                .satisfies(thrown -> {
                    EmbeddingClientException exception = (EmbeddingClientException) thrown;
                    assertThat(exception.failureType()).isEqualTo(type);
                    assertThat(exception.publicCode()).isEqualTo(type.publicCode());
                    assertThat(exception.failure().retryable()).isEqualTo(type.retryable());
                });
    }

    @Test
    void redactsSecretsAndBoundsDiagnostics() {
        String secret = "sk-live-1234567890abcdef";
        EmbeddingFailure failure = new EmbeddingFailure(
                EmbeddingFailureType.AUTHENTICATION_OR_AUTHORIZATION,
                "authorization: Bearer " + secret + " " + "document-content-".repeat(30));

        assertThat(failure.diagnostic()).hasSizeLessThanOrEqualTo(EmbeddingFailure.MAX_DIAGNOSTIC_LENGTH)
                .doesNotContain(secret).contains("[REDACTED]");
        assertThat(new EmbeddingClientException(failure).getMessage()).doesNotContain(secret);
    }

    @Test
    void disabledClientFailsClosedAndRejectsNullRequestAsLocalValidation() {
        assertThatThrownBy(() -> new DisabledEmbeddingClient().embed(EmbeddingRequest.single("text")))
                .isInstanceOf(EmbeddingClientException.class)
                .extracting(thrown -> ((EmbeddingClientException) thrown).failureType())
                .isEqualTo(EmbeddingFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED);
        assertThatThrownBy(() -> new DisabledEmbeddingClient().embed(null))
                .isInstanceOf(EmbeddingClientException.class)
                .extracting(thrown -> ((EmbeddingClientException) thrown).failureType())
                .isEqualTo(EmbeddingFailureType.LOCAL_VALIDATION);
    }
}
