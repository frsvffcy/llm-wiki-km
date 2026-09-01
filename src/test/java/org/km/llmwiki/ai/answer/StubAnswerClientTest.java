package org.km.llmwiki.ai.answer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class StubAnswerClientTest {

    @Test
    void returnsTheSameDeterministicResultForRepeatedRequests() {
        AnswerResult expected = new AnswerResult("deterministic answer",
                new AnswerProviderMetadata("stub", "offline-test-model"));
        StubAnswerClient client = StubAnswerClient.returning(expected);

        assertThat(client.generate(request())).isSameAs(expected);
        assertThat(client.generate(request())).isSameAs(expected);
    }

    @Test
    void disabledProductionBoundaryNeverPretendsToHaveGeneratedAnAnswer() {
        assertThatThrownBy(() -> new DisabledAnswerClient().generate(request()))
                .isInstanceOf(AnswerClientException.class)
                .satisfies(thrown -> {
                    AnswerClientException exception = (AnswerClientException) thrown;
                    assertThat(exception.failureType()).isEqualTo(
                            AnswerFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED);
                    assertThat(exception.getMessage()).contains("no real answer provider is configured");
                });
    }

    private static AnswerRequest request() {
        return new AnswerRequest("What is this?", AnswerContext.empty(),
                AnswerGenerationOptions.defaults());
    }
}
