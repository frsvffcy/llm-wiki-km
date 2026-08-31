package org.km.llmwiki.search;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Single rebuild worker prevents two clear-and-repopulate operations from interleaving. */
@Configuration
class FtsRebuildTaskConfiguration {

    @Bean("ftsRebuildTaskExecutor")
    TaskExecutor ftsRebuildTaskExecutor(FtsRebuildStartupReconciler startupReconciler) {
        startupReconciler.reconcile();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("fts-rebuild-");
        executor.initialize();
        return executor;
    }
}
