package org.km.llmwiki.ai.answer.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.km.llmwiki.ai.answer.AnswerClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring for the explicitly enabled OpenAI-compatible backend adapter. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiCompatibleAnswerProperties.class)
public class OpenAiCompatibleAnswerConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.ai.answer", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(AnswerClient.class)
    AnswerClient openAiCompatibleAnswerClient(OpenAiCompatibleAnswerProperties properties,
                                              ObjectMapper objectMapper) {
        return new OpenAiCompatibleAnswerClient(properties, objectMapper);
    }
}
