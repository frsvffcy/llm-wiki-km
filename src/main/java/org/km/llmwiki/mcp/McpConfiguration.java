package org.km.llmwiki.mcp;

import org.km.llmwiki.ai.ask.AskController;
import org.km.llmwiki.ai.provider.ProviderEgressService;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.system.SystemStatusService;
import org.km.llmwiki.source.SourceChunkLocatorService;
import org.km.llmwiki.web.RetrievalInspectorController;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the read-only local MCP adapter. The configuration is fail-closed:
 * without an explicitly configured backend-only auth token the adapter is disabled and the
 * endpoint answers typed {@code MCP_DISABLED} deterministically. Tools delegate to the
 * existing application contracts through their existing controller boundaries (system status,
 * search, retrieval inspector, source locator, ask) so there is exactly one implementation of
 * each semantic and no second retrieval/ask pipeline.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
public class McpConfiguration {

    @Bean
    public McpToolExecutor mcpToolExecutor(
            SystemStatusService systemStatusService,
            SearchService searchService,
            RetrievalInspectorController retrievalInspectorController,
            SourceChunkLocatorService sourceChunkLocatorService,
            AskController askController,
            ProviderEgressService providerEgressService) {
        return new McpToolExecutor(systemStatusService, searchService,
                retrievalInspectorController, sourceChunkLocatorService, askController,
                providerEgressService);
    }
}
