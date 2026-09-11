package org.km.llmwiki.ai.provider;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.answer.provider.openai.OpenAiCompatibleAnswerProperties;
import org.km.llmwiki.ai.embedding.provider.openai.OpenAiCompatibleEmbeddingProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ProviderEgressServiceTest {

    private ProviderEgressService service(boolean answerEnabled, String answerBaseUrl,
                                          boolean answerInsecure, String answerModel,
                                          boolean embeddingEnabled, String embeddingBaseUrl,
                                          boolean embeddingInsecure) {
        OpenAiCompatibleAnswerProperties answer = new OpenAiCompatibleAnswerProperties();
        answer.setEnabled(answerEnabled);
        if (answerBaseUrl != null) {
            answer.setBaseUrl(answerBaseUrl);
        }
        answer.setAllowInsecureTransport(answerInsecure);
        answer.setModel(answerModel);
        OpenAiCompatibleEmbeddingProperties embedding =
                new OpenAiCompatibleEmbeddingProperties();
        embedding.setEnabled(embeddingEnabled);
        if (embeddingBaseUrl != null) {
            embedding.setBaseUrl(embeddingBaseUrl);
        }
        embedding.setAllowInsecureTransport(embeddingInsecure);
        return new ProviderEgressService(answer, embedding);
    }

    @Test
    void disabledProvidersClassifyAsDisabledWithoutAnyEgressCategory() {
        List<ProviderEgressDescriptor> descriptors = service(
                false, null, false, null, false, null, false).descriptors();

        assertThat(descriptors).hasSize(2);
        assertThat(descriptors.get(0).purpose()).isEqualTo(
                ProviderEgressDescriptor.ProviderPurpose.ANSWER);
        assertThat(descriptors.get(0).destinationClass()).isEqualTo(ProviderDestination.DISABLED);
        assertThat(descriptors.get(0).egressCategories()).isEmpty();
        assertThat(descriptors.get(1).destinationClass()).isEqualTo(ProviderDestination.DISABLED);
        assertThat(descriptors.get(1).purpose())
                .isEqualTo(ProviderEgressDescriptor.ProviderPurpose.EMBEDDING);
        // A disabled provider must not leak its configured provider/model metadata either.
        assertThat(descriptors.get(0).providerType()).isNull();
        assertThat(descriptors.get(0).modelDisplayName()).isNull();
        assertThat(descriptors.get(1).providerType()).isNull();
        assertThat(descriptors.get(1).modelDisplayName()).isNull();
    }

    @Test
    void mixedAnswerLocalEmbeddingRemoteInsecureDestinationsAreDisclosedSeparately() {
        // Issue case 6: the answer boundary is local while the embedding boundary is remote
        // plain HTTP with the explicit opt-in; each descriptor must state its own class.
        List<ProviderEgressDescriptor> descriptors = service(true, "http://127.0.0.1:1234/v1",
                false, "local-model", true, "http://embed.example.com/v1", true).descriptors();

        assertThat(descriptors.get(0).destinationClass())
                .isEqualTo(ProviderDestination.LOCAL_LOOPBACK);
        assertThat(descriptors.get(1).destinationClass())
                .isEqualTo(ProviderDestination.REMOTE_INSECURE_OPT_IN);
        assertThat(descriptors.get(0).egressCategories()).isNotEmpty();
        assertThat(descriptors.get(1).egressCategories())
                .containsExactly(ProviderEgressCategory.EMBEDDING_INPUT_REPRESENTATION);
    }

    @Test
    void loopbackHttpIsClassifiedAsLocalWithoutSecrets() {
        List<ProviderEgressDescriptor> descriptors = service(true, "http://127.0.0.1:1234/v1",
                false, "local-model", true, "http://localhost:5678/v1", false).descriptors();

        assertThat(descriptors.get(0).destinationClass())
                .isEqualTo(ProviderDestination.LOCAL_LOOPBACK);
        assertThat(descriptors.get(1).destinationClass())
                .isEqualTo(ProviderDestination.LOCAL_LOOPBACK);
        assertThat(descriptors.get(0).modelDisplayName()).isEqualTo("local-model");
        // Data-category disclosure for the answer boundary is bounded and honest.
        assertThat(descriptors.get(0).egressCategories()).containsExactly(
                ProviderEgressCategory.QUESTION_TEXT,
                ProviderEgressCategory.EVIDENCE_CONTEXT_REPRESENTATION,
                ProviderEgressCategory.INSTRUCTION_CONTEXT,
                ProviderEgressCategory.GENERATION_SETTINGS,
                ProviderEgressCategory.PROVIDER_RESPONSE_METADATA);
        // The embedding boundary discloses only its own selected input representation.
        assertThat(descriptors.get(1).egressCategories())
                .containsExactly(ProviderEgressCategory.EMBEDDING_INPUT_REPRESENTATION);
    }

    @Test
    void remoteHttpsAndInsecureOptInClassifySeparately() {
        List<ProviderEgressDescriptor> https = service(true, "https://api.example.com/v1", false,
                null, false, null, false).descriptors();
        assertThat(https.get(0).destinationClass()).isEqualTo(ProviderDestination.REMOTE_SECURE);

        List<ProviderEgressDescriptor> insecure = service(true, "http://api.example.com/v1", true,
                null, false, null, false).descriptors();
        assertThat(insecure.get(0).destinationClass())
                .isEqualTo(ProviderDestination.REMOTE_INSECURE_OPT_IN);

        // Plain HTTP without the explicit opt-in is policy-invalid: the disclosure can never
        // disagree with the transport policy that would reject it.
        List<ProviderEgressDescriptor> rejected = service(true, "http://api.example.com/v1",
                false, null, false, null, false).descriptors();
        assertThat(rejected.get(0).destinationClass())
                .isEqualTo(ProviderDestination.UNAVAILABLE_OR_INVALID);
        assertThat(rejected.get(0).egressCategories()).isEmpty();
    }

    @Test
    void malformedEndpointsClassifyAsUnavailable() {
        List<ProviderEgressDescriptor> descriptors = service(true, "ht!tp://broken", false, null,
                true, "", false).descriptors();
        assertThat(descriptors.get(0).destinationClass())
                .isEqualTo(ProviderDestination.UNAVAILABLE_OR_INVALID);
        assertThat(descriptors.get(1).destinationClass())
                .isEqualTo(ProviderDestination.UNAVAILABLE_OR_INVALID);
    }

    @Test
    void descriptorsNeverExposeCredentialsRawEndpointsOrPaths() {
        OpenAiCompatibleAnswerProperties answer = new OpenAiCompatibleAnswerProperties();
        answer.setEnabled(true);
        answer.setBaseUrl("https://api.example.com/v1");
        answer.setApiKey("sk-secret-key");
        answer.setModel("offline-model");
        OpenAiCompatibleEmbeddingProperties embedding =
                new OpenAiCompatibleEmbeddingProperties();
        embedding.setEnabled(false);
        ProviderEgressService service = new ProviderEgressService(answer, embedding);

        List<ProviderEgressDescriptor> descriptors = service.descriptors();

        assertThat(descriptors).allSatisfy(descriptor -> {
            String text = String.valueOf(descriptor);
            assertThat(text).doesNotContain("sk-secret-key");
            assertThat(text).doesNotContain("https://");
            assertThat(text).doesNotContain("baseUrl");
            assertThat(text).doesNotContain("apiKey");
            assertThat(text).doesNotContain("/");
        });
        assertThat(descriptors.get(0).providerType()).isEqualTo("openai-compatible");
        assertThat(descriptors.get(0).modelDisplayName()).isEqualTo("offline-model");
    }
}
