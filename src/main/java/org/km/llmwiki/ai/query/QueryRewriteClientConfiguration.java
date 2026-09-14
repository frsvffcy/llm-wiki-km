package org.km.llmwiki.ai.query;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies a fail-closed rewrite boundary unless an adapter is explicitly configured. */
@Configuration(proxyBeanMethods = false)
public class QueryRewriteClientConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "app.ai.query-transformation.provider", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(QueryRewriteClient.class)
    QueryRewriteClient disabledQueryRewriteClient() {
        return new DisabledQueryRewriteClient();
    }
}
