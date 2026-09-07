package org.km.llmwiki.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.GraphProjectionBackendFactory;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphProjectionLifecycleService;
import org.km.llmwiki.graph.GraphProjectionVerificationStatus;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
class GraphProjectionConfigurationTest {

    private static final GraphWorkspaceScope WORKSPACE = new GraphWorkspaceScope(41);

    @TempDir
    Path tempDir;

    @Test
    void defaultDisabledDoesNotCreateBackendFactoryOrFilesystemPath() {
        Path backendPath = tempDir.resolve("disabled");

        contextRunner(backendPath).run(context -> {
            assertThat(context).hasSingleBean(GraphProjectionLifecycleService.class);
            assertThat(context).doesNotHaveBean(GraphProjectionBackendFactory.class);
            assertThat(context.getBean(GraphProjectionLifecycleService.class)
                    .readiness(WORKSPACE).status())
                    .isEqualTo(GraphProjectionVerificationStatus.DISABLED);
            assertThat(Files.exists(backendPath)).isFalse();
        });
    }

    @Test
    void enabledArcadeDbCreatesLazyFactoryButDoesNotClaimReadiness() {
        Path backendPath = tempDir.resolve("enabled");

        contextRunner(backendPath)
                .withPropertyValues("app.graph.projection.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(GraphProjectionBackendFactory.class);
                    assertThat(context.getBean(GraphProjectionLifecycleService.class)
                            .readiness(WORKSPACE).status())
                            .isEqualTo(GraphProjectionVerificationStatus.NOT_READY);
                    assertThat(Files.exists(backendPath)).isFalse();
                });
    }

    @Test
    void unknownProviderRemainsNotConfiguredWithoutOpeningBackend() {
        Path backendPath = tempDir.resolve("unknown");

        contextRunner(backendPath)
                .withPropertyValues(
                        "app.graph.projection.enabled=true",
                        "app.graph.projection.provider=unknown")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(GraphProjectionBackendFactory.class);
                    assertThat(context.getBean(GraphProjectionLifecycleService.class)
                            .readiness(WORKSPACE).status())
                            .isEqualTo(GraphProjectionVerificationStatus.NOT_CONFIGURED);
                    assertThat(Files.exists(backendPath)).isFalse();
                });
    }

    private ApplicationContextRunner contextRunner(Path backendPath) {
        GraphProjectionLifecycleRepository repository = mock(
                GraphProjectionLifecycleRepository.class);
        when(repository.find(WORKSPACE)).thenReturn(Optional.empty());
        return new ApplicationContextRunner()
                .withUserConfiguration(GraphProjectionConfiguration.class)
                .withBean(org.km.llmwiki.graph.GraphCanonicalCurrentness.class,
                        () -> mock(org.km.llmwiki.graph.GraphCanonicalCurrentness.class))
                .withBean(org.km.llmwiki.graph.GraphProjectionInputAssembler.class,
                        () -> mock(org.km.llmwiki.graph.GraphProjectionInputAssembler.class))
                .withBean(GraphProjectionLifecycleRepository.class, () -> repository)
                .withPropertyValues("app.graph.projection.path=" + backendPath);
    }
}
