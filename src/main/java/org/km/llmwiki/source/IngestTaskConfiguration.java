package org.km.llmwiki.source;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** One bounded ingest worker prevents batch uploads from creating unbounded extraction concurrency. */
@Configuration
class IngestTaskConfiguration {

    @Bean("ingestTaskExecutor")
    ThreadPoolTaskExecutor ingestTaskExecutor(IngestStartupReconciler startupReconciler) {
        startupReconciler.reconcile();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("ingest-");
        executor.initialize();
        return executor;
    }
}
