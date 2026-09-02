package org.km.llmwiki.ai.embedding;

/** Safe production default until an embedding provider adapter is explicitly enabled. */
public final class DisabledEmbeddingClient implements EmbeddingClient {

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        if (request == null) {
            throw new EmbeddingClientException(EmbeddingFailureType.LOCAL_VALIDATION,
                    "embedding request is required");
        }
        throw new EmbeddingClientException(EmbeddingFailureType.CONFIGURATION_UNAVAILABLE_OR_DISABLED,
                "no real embedding provider is configured or enabled");
    }
}
