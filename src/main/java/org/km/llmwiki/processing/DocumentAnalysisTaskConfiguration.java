package org.km.llmwiki.processing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Dedicated worker for document analysis so HTTP requests never wait for LLM work. */
@Configuration
public class DocumentAnalysisTaskConfiguration {

    @Bean("documentAnalysisTaskExecutor")
    TaskExecutor documentAnalysisTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("document-analysis-");
        executor.initialize();
        return executor;
    }
}
