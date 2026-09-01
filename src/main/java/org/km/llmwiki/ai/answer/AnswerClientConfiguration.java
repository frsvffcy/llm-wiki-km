package org.km.llmwiki.ai.answer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Installs a fail-closed answer boundary when no real provider adapter is configured. */
@Configuration(proxyBeanMethods = false)
public class AnswerClientConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.ai.answer", name = "enabled", havingValue = "false",
            matchIfMissing = true)
    @ConditionalOnMissingBean(AnswerClient.class)
    AnswerClient disabledAnswerClient() {
        return new DisabledAnswerClient();
    }
}
