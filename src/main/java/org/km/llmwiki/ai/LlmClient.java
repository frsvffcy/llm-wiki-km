package org.km.llmwiki.ai;

/**
 * Provider-neutral boundary for document analysis. Implementations return only validated,
 * structured results and never perform persistence or filesystem work.
 */
public interface LlmClient {

    LlmAnalysisResult analyze(DocumentAnalysisRequest request) throws LlmClientException;
}
