package org.km.llmwiki.ai.query.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.km.llmwiki.ai.query.QueryRewriteClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiCompatibleQueryRewriteProperties.class)
public class OpenAiCompatibleQueryRewriteConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "app.ai.query-transformation.provider", name = "enabled",
            havingValue = "true")
    @ConditionalOnMissingBean(QueryRewriteClient.class)
    QueryRewriteClient openAiCompatibleQueryRewriteClient(
            OpenAiCompatibleQueryRewriteProperties properties, ObjectMapper mapper) {
        return new OpenAiCompatibleQueryRewriteClient(properties, mapper);
    }
}
