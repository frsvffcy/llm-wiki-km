package org.km.llmwiki.search;

import org.km.llmwiki.search.embedding.EmbeddingProjectionStartupReconciler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Single rebuild worker prevents two clear-and-repopulate operations from interleaving. */
@Configuration
class FtsRebuildTaskConfiguration {

    @Bean("ftsRebuildTaskExecutor")
    TaskExecutor ftsRebuildTaskExecutor(FtsRebuildStartupReconciler startupReconciler,
                                        EmbeddingProjectionStartupReconciler embeddingReconciler) {
        startupReconciler.reconcile();
        embeddingReconciler.reconcile();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("fts-rebuild-");
        executor.initialize();
        return executor;
    }

    /**
     * Embedding work is deliberately isolated from the FTS worker.  Both
     * projections are asynchronous, but an embedding provider call can be
     * materially slower than a local FTS rebuild and must not delay canonical
     * wiki/source indexing or its repair loop.
     */
    @Bean("embeddingProjectionTaskExecutor")
    TaskExecutor embeddingProjectionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("embedding-projection-");
        executor.initialize();
        return executor;
    }
}
