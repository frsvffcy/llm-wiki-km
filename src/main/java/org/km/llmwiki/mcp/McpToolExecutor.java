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
        McpToolInputContract contract = McpCapabilityManifest.contractFor(toolName);
        if (contract == null) {
            // Unreachable through the controller, which routes unknown tools to a protocol
            // error first; kept as a fail-closed internal invariant, never a success envelope.
            throw new IllegalStateException("unknown tool: " + toolName);
        }
        McpValidatedArguments validated;
        try {
            validated = contract.validate(arguments);
        } catch (McpToolInputException invalid) {
            return McpToolResult.failure(McpToolError.INVALID_REQUEST, invalid.getMessage());
        }
        try {
            return switch (toolName) {
                case McpCapabilityManifest.TOOL_STATUS -> status();
                case McpCapabilityManifest.TOOL_SEARCH -> search(validated);
                case McpCapabilityManifest.TOOL_RETRIEVAL_INSPECT -> retrievalInspect(validated);
                case McpCapabilityManifest.TOOL_SOURCE_LOCATOR -> sourceLocator(validated);
                case McpCapabilityManifest.TOOL_ASK -> ask(validated);
                default -> throw new IllegalStateException("unknown tool: " + toolName);
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

    private McpToolResult status() {
        return McpToolResult.success(systemStatusService.getStatus(), List.of());
    }

    private McpToolResult search(McpValidatedArguments arguments) {
        var page = searchService.search(arguments.string("query"),
                arguments.string("corpus"),
                arguments.stringOr("pageType", null),
                arguments.longOrNull("documentId"),
                arguments.intValue("page"),
                arguments.intValue("size"));
        // Identical projection to the REST search contract: same DTO, same fields, zero
        // drift between the REST pipeline and the MCP tool (issue H).
        return McpToolResult.success(page, List.of());
    }

    private McpToolResult retrievalInspect(McpValidatedArguments arguments) {
        // Same shared inspector boundary the REST controller uses: identical validation and
        // the same safe DTO projection, so no second inspection path exists. The REST HTTP
        // envelope stays with the REST adapter; only the application projection is shared.
        return McpToolResult.success(
                RetrievalInspectionMapper.toResponse(retrievalInspectorService.inspect(
                        RetrievalInspectionMapper.validate(arguments.string("question"),
                                arguments.string("mode")))),
                List.of());
    }

    private McpToolResult sourceLocator(McpValidatedArguments arguments) {
        return McpToolResult.success(
                sourceChunkLocatorService.locate(arguments.longValue("chunkId")), List.of());
    }

    private McpToolResult ask(McpValidatedArguments arguments) {
        // Same shared application boundary the REST controller uses: identical request
        // parsing/validation, identical orchestration (retrieval → qualification → rerank →
        // projector → provider → grounded/citation validation), and identical typed failure
        // mapping via AskApiException. Unadvertised arguments never reach this point: the
        // shared contract rejects them as unsupported before any service executes.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", arguments.string("question"));
        body.put("retrievalMode", arguments.string("retrievalMode"));
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

}
