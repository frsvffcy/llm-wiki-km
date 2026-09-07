package org.km.llmwiki.config;

import org.km.llmwiki.graph.GraphProjectionBackendFactory;
import org.km.llmwiki.graph.GraphCanonicalCurrentness;
import org.km.llmwiki.graph.GraphProjectionInputAssembler;
import org.km.llmwiki.graph.GraphProjectionIngressService;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphProjectionLifecycleService;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.persistence.graph.arcadedb.ArcadeDbGraphProjectionBackendFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the optional Graph projection without making backend presence imply readiness. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GraphProjectionProperties.class)
public class GraphProjectionConfiguration {

    @Bean
    GraphProjectionLifecycleService graphProjectionLifecycleService(
            GraphProjectionProperties properties,
            GraphProjectionLifecycleRepository repository,
            GraphCanonicalCurrentness currentness,
            ObjectProvider<GraphProjectionBackendFactory> backendFactory) {
        return new GraphProjectionLifecycleService(properties.isEnabled(),
                properties.getProvider(), GraphProjectionVersion.initial(), repository,
                backendFactory.getIfAvailable(), currentness);
    }

    @Bean
    GraphProjectionIngressService graphProjectionIngressService(GraphProjectionInputAssembler assembler,
                                                               GraphProjectionLifecycleService lifecycle) {
        return new GraphProjectionIngressService(assembler, lifecycle);
    }

    @Bean
    ApplicationRunner graphProjectionRecoveryRunner(GraphProjectionLifecycleService service) {
        return arguments -> service.reconcileInterruptedOperations();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.graph.projection", name = "enabled",
            havingValue = "true")
    @ConditionalOnProperty(prefix = "app.graph.projection", name = "provider",
            havingValue = ArcadeDbGraphProjectionBackendFactory.PROVIDER, matchIfMissing = true)
    GraphProjectionBackendFactory arcadeDbGraphProjectionBackendFactory(
            GraphProjectionProperties properties) {
        return new ArcadeDbGraphProjectionBackendFactory(properties.getPath(),
                GraphProjectionVersion.initial());
    }
}
