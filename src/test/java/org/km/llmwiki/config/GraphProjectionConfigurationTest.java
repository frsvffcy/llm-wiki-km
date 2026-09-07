package org.km.llmwiki.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.GraphProjectionBackendFactory;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphProjectionLifecycleService;
import org.km.llmwiki.graph.GraphProjectionVerificationStatus;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.graph.GraphTraversalBounds;
import org.km.llmwiki.graph.GraphTraversalBackendFactory;
import org.km.llmwiki.graph.GraphTraversalOrdering;
import org.km.llmwiki.graph.GraphTraversalQuery;
import org.km.llmwiki.graph.GraphTraversalService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
            assertThat(context).hasSingleBean(GraphTraversalService.class);
            assertThat(context).doesNotHaveBean(GraphProjectionBackendFactory.class);
            assertThat(context).doesNotHaveBean(GraphTraversalBackendFactory.class);
            assertThat(context.getBean(GraphProjectionLifecycleService.class)
                    .readiness(WORKSPACE).status())
                    .isEqualTo(GraphProjectionVerificationStatus.DISABLED);
            assertThat(Files.exists(backendPath)).isFalse();
            assertThatThrownBy(() -> context.getBean(GraphTraversalService.class)
                    .traverse(query()))
                    .isInstanceOf(GraphProjectionException.class)
                    .extracting(failure -> ((GraphProjectionException) failure).failureType())
                    .isEqualTo(GraphProjectionFailureType.CAPABILITY_DISABLED);
        });
    }

    @Test
    void enabledArcadeDbCreatesLazyFactoryButDoesNotClaimReadiness() {
        Path backendPath = tempDir.resolve("enabled");

        contextRunner(backendPath)
                .withPropertyValues("app.graph.projection.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(GraphProjectionBackendFactory.class);
                    assertThat(context).hasSingleBean(GraphTraversalBackendFactory.class);
                    assertThat(context).hasSingleBean(GraphTraversalService.class);
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

    private static GraphTraversalQuery query() {
        var authority = new org.km.llmwiki.graph.GraphAuthorityReference(WORKSPACE,
                org.km.llmwiki.graph.GraphAuthorityKind.WIKI_PAGE, "seed");
        var snapshot = org.km.llmwiki.graph.GraphProjectionSnapshot.fromProof(WORKSPACE,
                org.km.llmwiki.graph.GraphProjectionVersion.initial(), 1, "a".repeat(64));
        return new GraphTraversalQuery(WORKSPACE,
                List.of(org.km.llmwiki.graph.GraphEntityIdentity.fromAuthority(authority,
                        org.km.llmwiki.graph.GraphEntityType.WIKI_PAGE)),
                Set.of(org.km.llmwiki.graph.GraphRelationType.LINKS_TO),
                new GraphTraversalBounds(1, 1, 1, 1, 1, 1),
                GraphTraversalOrdering.DEPTH_SEED_ENTITY_PATH_V1, snapshot);
    }
}
