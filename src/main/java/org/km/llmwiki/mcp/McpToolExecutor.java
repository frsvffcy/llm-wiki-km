package org.km.llmwiki.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.km.llmwiki.ai.ask.AskApiException;
import org.km.llmwiki.ai.ask.AskApiRequest;
import org.km.llmwiki.ai.ask.AskApplicationService;
import org.km.llmwiki.ai.ask.AskFailureType;
import org.km.llmwiki.ai.ask.AskResult;
import org.km.llmwiki.ai.ask.AskStatus;
import org.km.llmwiki.ai.provider.ProviderEgressService;
import org.km.llmwiki.rag.RetrievalInspectorService;
import org.km.llmwiki.rag.RetrievalUnavailableException;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.source.SourceChunkLocatorService;
import org.km.llmwiki.source.SourceChunkNotFoundException;
import org.km.llmwiki.system.SystemStatusService;
import org.km.llmwiki.web.RetrievalInspectionMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application-owned MCP tool executor. Every tool delegates to the EXISTING application
 * service / DTO projection — this adapter never touches SQLite, the vault/archive filesystem,
 * ArcadeDB, sqlite-vec, provider endpoints, or provider keys directly, and never creates a
 * second retrieval/ask pipeline. Search/Inspector/Locator/Ask reuse the current
 * implementations with identical semantics, and the ask tool's egress disclosure reuses the
 * #323 provider-egress service plus the #310 execution-level provider usage status, with the
 * configuration level and execution level clearly labelled.
 */
public class McpToolExecutor {

    private final SystemStatusService systemStatusService;
    private final SearchService searchService;
    private final RetrievalInspectorService retrievalInspectorService;
    private final SourceChunkLocatorService sourceChunkLocatorService;
    private final AskApplicationService askApplication;
    private final ProviderEgressService providerEgressService;

    public McpToolExecutor(SystemStatusService systemStatusService,
                           SearchService searchService,
                           RetrievalInspectorService retrievalInspectorService,
                           SourceChunkLocatorService sourceChunkLocatorService,
                           AskApplicationService askApplication,
                           ProviderEgressService providerEgressService) {
        this.systemStatusService = systemStatusService;
        this.searchService = searchService;
        this.retrievalInspectorService = retrievalInspectorService;
        this.sourceChunkLocatorService = sourceChunkLocatorService;
        this.askApplication = askApplication;
        this.providerEgressService = providerEgressService;
    }

    public McpToolResult execute(String toolName, JsonNode arguments) {
        if (!McpCapabilityManifest.isKnown(toolName)) {
            return McpToolResult.failure(McpToolError.UNSUPPORTED_TOOL,
                    "unsupported tool; read-only tools only: " + supportedNames());
        }
        try {
            return switch (toolName) {
                case McpCapabilityManifest.TOOL_STATUS -> status();
                case McpCapabilityManifest.TOOL_SEARCH -> search(arguments);
                case McpCapabilityManifest.TOOL_RETRIEVAL_INSPECT -> retrievalInspect(arguments);
                case McpCapabilityManifest.TOOL_SOURCE_LOCATOR -> sourceLocator(arguments);
                case McpCapabilityManifest.TOOL_ASK -> ask(arguments);
                default -> McpToolResult.failure(McpToolError.UNSUPPORTED_TOOL,
                        "unsupported tool");
            };
        } catch (RetrievalUnavailableException exception) {
            return McpToolResult.failure(McpToolError.RETRIEVAL_UNAVAILABLE,
                    "retrieval dependency is unavailable");
        } catch (SourceChunkNotFoundException exception) {
            return McpToolResult.failure(McpToolError.NOT_FOUND, "source chunk not found");
        } catch (AskApiException exception) {
            return McpToolResult.failure(providerError(exception.failureType()),
                    exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return McpToolResult.failure(McpToolError.INVALID_REQUEST, exception.getMessage());
        } catch (org.km.llmwiki.workspace.NoActiveWorkspaceException exception) {
            return McpToolResult.failure(McpToolError.INVALID_REQUEST, "no active workspace");
        } catch (IllegalStateException exception) {
            return McpToolResult.failure(McpToolError.LOCAL_VALIDATION, exception.getMessage());
        }
    }

    private static String supportedNames() {
        return String.join(", ", McpCapabilityManifest.tools().keySet());
    }

    private McpToolResult status() {
        return McpToolResult.success(systemStatusService.getStatus(), List.of());
    }

    private McpToolResult search(JsonNode arguments) {
        String query = text(arguments, "query");
        if (query == null || query.isBlank()
                || query.codePointCount(0, query.length()) > 256) {
            return McpToolResult.failure(McpToolError.INVALID_REQUEST,
                    "query is required and must not exceed 256 code points");
        }
        var page = searchService.search(query,
                textOr(arguments, "corpus", "WIKI"),
                textOrNull(arguments, "pageType"),
                longOrNull(arguments, "documentId"),
                intOrNull(arguments, "page", 1),
                intOrNull(arguments, "size", 20));
        // Identical projection to the REST search contract: same DTO, same fields, zero
        // drift between the REST pipeline and the MCP tool (issue H).
        return McpToolResult.success(page, List.of());
    }

    private McpToolResult retrievalInspect(JsonNode arguments) {
        String question = text(arguments, "question");
        if (question == null || question.isBlank()) {
            return McpToolResult.failure(McpToolError.INVALID_REQUEST, "question is required");
        }
        // Same shared inspector boundary the REST controller uses: identical validation and
        // the same safe DTO projection, so no second inspection path exists. The REST HTTP
        // envelope stays with the REST adapter; only the application projection is shared.
        return McpToolResult.success(
                RetrievalInspectionMapper.toResponse(retrievalInspectorService.inspect(
                        RetrievalInspectionMapper.validate(question, textOr(arguments, "mode",
                                "HYBRID_GRAPH")))),
                List.of());
    }

    private McpToolResult sourceLocator(JsonNode arguments) {
        Long chunkId = longOrNull(arguments, "chunkId");
        if (chunkId == null || chunkId <= 0) {
            return McpToolResult.failure(McpToolError.INVALID_REQUEST, "chunkId is required");
        }
        return McpToolResult.success(sourceChunkLocatorService.locate(chunkId), List.of());
    }

    private McpToolResult ask(JsonNode arguments) {
        String question = text(arguments, "question");
        if (question == null || question.isBlank()) {
            return McpToolResult.failure(McpToolError.INVALID_REQUEST, "question is required");
        }
        // Same shared application boundary the REST controller uses: identical request
        // parsing/validation, identical orchestration (retrieval → qualification → rerank →
        // projector → provider → grounded/citation validation), and identical typed failure
        // mapping via AskApiException. The ask API request contract intentionally has no
        // maxItems/maxCharacters fields, so those arguments are rejected as unsupported by
        // the request validation itself.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("retrievalMode", textOr(arguments, "retrievalMode", "HYBRID_FTS"));
        for (String extra : List.of("maxItems", "maxCharacters")) {
            if (arguments != null && arguments.hasNonNull(extra)) {
                return McpToolResult.failure(McpToolError.INVALID_REQUEST,
                        "unsupported ask argument: " + extra);
            }
        }
        AskApiRequest request;
        try {
            request = askApplication.parseRequest(McpJsonRpc.valueToTree(body));
        } catch (IllegalArgumentException invalid) {
            return McpToolResult.failure(McpToolError.INVALID_REQUEST, invalid.getMessage());
        }
        AskResult result = askApplication.execute(request);
        if (result.status() == AskStatus.FAILED) {
            AskApiException failure = askApplication.failure(result);
            return McpToolResult.failure(providerError(failure.failureType()),
                    failure.getMessage());
        }
        return McpToolResult.success(askApplication.project(result), egressLines(result));
    }

    /**
     * Execution-level facts come from the SAME application ask result the tool returns:
     * {@code NOT_ATTEMPTED} means no provider call happened (for example no evidence);
     * AVAILABLE/UNAVAILABLE mean a provider call was attempted or completed. This is
     * deliberately read from the application execution metadata — never from the pre-request
     * configuration descriptor and never through the REST response wrapper — so a mid-flight
     * configuration change can never be presented as an execution-bound fact.
     */
    private List<McpToolResult.ProviderEgressLine> egressLines(AskResult result) {
        List<McpToolResult.ProviderEgressLine> lines = new ArrayList<>();
        providerEgressService.descriptors().forEach(descriptor -> lines.add(
                new McpToolResult.ProviderEgressLine(
                        descriptor.purpose().name(), descriptor.destinationClass().name(),
                        "CONFIGURATION")));
        lines.add(new McpToolResult.ProviderEgressLine("ANSWER", result.executionMetadata()
                .contextDiagnostics().providerUsageStatus().name(), "EXECUTION"));
        return lines;
    }

    private static McpToolError providerError(AskFailureType failureType) {
        return switch (failureType) {
            case PROVIDER_CONFIGURATION_UNAVAILABLE ->
                    McpToolError.PROVIDER_CONFIGURATION_UNAVAILABLE;
            case PROVIDER_AUTHENTICATION_OR_AUTHORIZATION ->
                    McpToolError.PROVIDER_AUTHENTICATION_OR_AUTHORIZATION;
            case PROVIDER_RATE_LIMIT_OR_QUOTA -> McpToolError.PROVIDER_RATE_LIMIT_OR_QUOTA;
            case PROVIDER_TIMEOUT_OR_NETWORK_UNAVAILABLE ->
                    McpToolError.PROVIDER_TIMEOUT_OR_NETWORK_UNAVAILABLE;
            case PROVIDER_SERVER_FAILURE -> McpToolError.PROVIDER_SERVER_FAILURE;
            case PROVIDER_INVALID_RESPONSE -> McpToolError.PROVIDER_INVALID_RESPONSE;
            case LOCAL_VALIDATION -> McpToolError.LOCAL_VALIDATION;
            case RETRIEVAL_UNAVAILABLE -> McpToolError.RETRIEVAL_UNAVAILABLE;
            case RETRIEVAL_VECTOR_UNAVAILABLE -> McpToolError.RETRIEVAL_UNAVAILABLE;
        };
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        return node.get(field).asText();
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String textOrNull(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : value;
    }

    private static Long longOrNull(JsonNode node, String field) {
        return node == null || !node.hasNonNull(field) ? null : node.get(field).asLong();
    }

    private static Integer intOrNull(JsonNode node, String field, Integer fallback) {
        if (node == null || !node.hasNonNull(field)) {
            return fallback;
        }
        return node.get(field).asInt();
    }
}
