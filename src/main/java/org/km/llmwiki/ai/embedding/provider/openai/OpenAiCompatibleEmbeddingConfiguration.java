package org.km.llmwiki.ai.embedding.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.km.llmwiki.ai.embedding.EmbeddingClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring for the explicitly enabled OpenAI-compatible embedding adapter. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiCompatibleEmbeddingProperties.class)
public class OpenAiCompatibleEmbeddingConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.ai.embedding", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(EmbeddingClient.class)
    EmbeddingClient openAiCompatibleEmbeddingClient(OpenAiCompatibleEmbeddingProperties properties,
                                                    ObjectMapper objectMapper) {
        return new OpenAiCompatibleEmbeddingClient(properties, objectMapper);
    }
}
