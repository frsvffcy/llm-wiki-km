package org.km.llmwiki.ai.embedding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class EmbeddingClientConfigurationIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private EmbeddingClient embeddingClient;

    @Test
    void installsDisabledClientByDefaultAndFailsClosed() {
        assertThat(embeddingClient).isInstanceOf(DisabledEmbeddingClient.class);
        assertThatThrownBy(() -> embeddingClient.embed(EmbeddingRequest.single("configuration test")))
                .isInstanceOf(EmbeddingClientException.class)
                .extracting(thrown -> ((EmbeddingClientException) thrown).failureType())
                .isEqualTo(EmbeddingFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED);
    }
}
