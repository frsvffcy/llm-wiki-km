package org.km.llmwiki.ai.ask;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

/**
 * Shared application-facing Ask boundary used by the REST and MCP adapters.
 *
 * <p>Both adapters delegate request parsing, orchestration, failure mapping, and the safe
 * response projection to this boundary instead of calling each other's adapter classes, so
 * there is exactly one implementation of each Ask semantic and no second Ask pipeline.
 * {@link AskService} stays the orchestration authority; this boundary adds no retrieval,
 * rerank, projection, provider, or citation policy of its own.
 *
 * <p>Transport separation stays intact: this boundary never depends on servlet/HTTP/MCP
 * types, and the REST error envelope ({@code ApiError}) and the MCP JSON-RPC error mapping
 * each remain the responsibility of their own adapter.
 */
@Service
public class AskApplicationService {

    private final AskService askService;

    public AskApplicationService(AskService askService) {
        this.askService = askService;
    }

    /**
     * Shared strict request parsing: a JSON body becomes a validated {@link AskApiRequest}
     * with identical rules for every adapter (unknown-field rejection, textual fields,
     * blank/strip/4000-code-point question bound, required retrieval mode).
     */
    public AskApiRequest parseRequest(JsonNode body) {
        return AskApiRequest.fromJson(body);
    }

    /**
     * Shared execution: delegates orchestration to {@link AskService}. Returns the
     * application result, including FAILED results, so each adapter can apply its own
     * transport failure mapping.
     */
    public AskResult execute(AskApiRequest request) {
        return askService.ask(request.toApplicationRequest());
    }

    /**
     * Shared safe projection: an application {@link AskResult} becomes the transport-neutral
     * {@link AskApiResponse} projection both adapters embed in their own envelopes.
     */
    public AskApiResponse project(AskResult result) {
        return AskApiResponse.from(result);
    }

    /**
     * Shared failure mapping: a FAILED {@link AskResult} becomes the typed
     * {@link AskApiException} each adapter then maps to its own transport representation.
     */
    public AskApiException failure(AskResult result) {
        if (result.status() != AskStatus.FAILED) {
            throw new IllegalArgumentException("only FAILED Ask results map to a failure");
        }
        return new AskApiException(result.failure()
                .orElseThrow(() -> new IllegalStateException("failed Ask result has no failure"))
                .type());
    }
}
