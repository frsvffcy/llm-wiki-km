package org.km.llmwiki.ai.embedding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Installs a fail-closed embedding boundary when no enabled provider adapter exists. */
@Configuration(proxyBeanMethods = false)
public class EmbeddingClientConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.ai.embedding", name = "enabled", havingValue = "false",
            matchIfMissing = true)
    @ConditionalOnMissingBean(EmbeddingClient.class)
    EmbeddingClient disabledEmbeddingClient() {
        return new DisabledEmbeddingClient();
    }
}
