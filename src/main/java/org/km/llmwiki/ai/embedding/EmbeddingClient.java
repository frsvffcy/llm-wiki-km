package org.km.llmwiki.ai.embedding;

/**
 * Provider-neutral boundary for turning canonical text inputs into vectors.
 *
 * <p>Implementations must not perform persistence, retrieval, search, or workspace work, and
 * provider-specific request/response DTOs must not cross this boundary.
 */
public interface EmbeddingClient {

    EmbeddingResult embed(EmbeddingRequest request) throws EmbeddingClientException;
}
