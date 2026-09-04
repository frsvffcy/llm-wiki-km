package org.km.llmwiki.ai;

import java.util.Objects;
import java.util.function.Function;

/** Offline provider used by tests; it validates a supplied JSON response without network access. */
public final class StubLlmClient implements LlmClient {

    private final LlmAnalysisContract contract;
    private final Function<DocumentAnalysisRequest, String> responseFactory;

    public StubLlmClient(LlmAnalysisContract contract, Function<DocumentAnalysisRequest, String> responseFactory) {
        this.contract = Objects.requireNonNull(contract, "contract must not be null");
        this.responseFactory = Objects.requireNonNull(responseFactory, "responseFactory must not be null");
    }

    @Override
    public LlmAnalysisResult analyze(DocumentAnalysisRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return contract.parse(responseFactory.apply(request));
    }
}
