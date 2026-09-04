package org.km.llmwiki.ai.answer;

/**
 * Provider-neutral boundary for grounded answer generation.
 *
 * <p>Implementations are responsible only for generation. They must not perform persistence,
 * filesystem, search, or retrieval work, and provider-specific DTOs must not cross this boundary.
 */
public interface AnswerClient {

    AnswerResult generate(AnswerRequest request) throws AnswerClientException;
}
