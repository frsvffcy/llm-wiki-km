package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.workspace.CreateWorkspaceRequest;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class ActiveWorkspaceWikiPathResolverTest {

    private final WikiPathContract contract = new WikiPathContract();

    @Test
    void resolvesPathAgainstActiveWorkspaceVault(@TempDir Path tempDir) throws IOException {
        Path wsRoot = tempDir.resolve("ws1");
        Path vaultRoot = wsRoot.resolve("vault");
        Path conceptsDir = vaultRoot.resolve("concepts");
        Files.createDirectories(conceptsDir);

        WorkspaceService mockWorkspaceService = Mockito.mock(WorkspaceService.class);
        WorkspaceResponse mockResponse = new WorkspaceResponse(
                1L, "Workspace 1", wsRoot.toString(),
                wsRoot.resolve("inbox").toString(),
                wsRoot.resolve("archive").toString(),
                vaultRoot.toString(),
                wsRoot.resolve("data").toString(),
                wsRoot.resolve("config").toString(),
                "ACTIVE", "2026-08-28T00:00:00Z", "2026-08-28T00:00:00Z");

        when(mockWorkspaceService.findActiveWithoutValidation()).thenReturn(Optional.of(mockResponse));

        ActiveWorkspaceWikiPathResolver resolver =
                new ActiveWorkspaceWikiPathResolver(mockWorkspaceService, contract);

        Path resolved = resolver.resolveAndValidateRealPath(WikiPageType.CONCEPT, "Spring Boot 3");

        assertThat(resolved.getParent()).isEqualTo(conceptsDir.toRealPath());
        assertThat(resolved.getFileName().toString()).isEqualTo("spring-boot-3.md");
        assertThat(Files.exists(resolved)).isFalse(); // No side effects
    }

    @Test
    void throwsExceptionWhenNoActiveWorkspace() {
        WorkspaceService mockWorkspaceService = Mockito.mock(WorkspaceService.class);
        when(mockWorkspaceService.findActiveWithoutValidation()).thenReturn(Optional.empty());

        ActiveWorkspaceWikiPathResolver resolver =
                new ActiveWorkspaceWikiPathResolver(mockWorkspaceService, contract);

        assertThatThrownBy(() -> resolver.resolveAndValidateRealPath(WikiPageType.CONCEPT, "Spring Boot 3"))
                .isInstanceOf(NoActiveWorkspaceException.class);
        assertThatThrownBy(() -> resolver.getActiveVaultRoot())
                .isInstanceOf(NoActiveWorkspaceException.class);
    }

    @Test
    void preventsForeignWorkspaceCrossAccess(@TempDir Path tempDir) throws IOException {
        // Setup Workspace A (active) and Workspace B (inactive)
        Path wsRootA = tempDir.resolve("ws_a");
        Path vaultRootA = wsRootA.resolve("vault");
        Files.createDirectories(vaultRootA.resolve("concepts"));

        Path wsRootB = tempDir.resolve("ws_b");
        Path vaultRootB = wsRootB.resolve("vault");
        Files.createDirectories(vaultRootB.resolve("concepts"));

        WorkspaceService mockWorkspaceService = Mockito.mock(WorkspaceService.class);
        WorkspaceResponse activeWorkspaceA = new WorkspaceResponse(
                1L, "Workspace A", wsRootA.toString(),
                wsRootA.resolve("inbox").toString(),
                wsRootA.resolve("archive").toString(),
                vaultRootA.toString(),
                wsRootA.resolve("data").toString(),
                wsRootA.resolve("config").toString(),
                "ACTIVE", "2026-08-28T00:00:00Z", "2026-08-28T00:00:00Z");

        // Active workspace is always Workspace A
        when(mockWorkspaceService.findActiveWithoutValidation()).thenReturn(Optional.of(activeWorkspaceA));

        ActiveWorkspaceWikiPathResolver resolver =
                new ActiveWorkspaceWikiPathResolver(mockWorkspaceService, contract);

        // Path resolution will always bind strictly to Workspace A
        Path resolved = resolver.resolveAndValidateRealPath(WikiPageType.CONCEPT, "My Document");
        assertThat(resolved.startsWith(vaultRootA.toRealPath())).isTrue();
        assertThat(resolved.startsWith(vaultRootB.toRealPath())).isFalse();
    }

    @Test
    void checksSubDirectoryExists(@TempDir Path tempDir) throws IOException {
        Path wsRoot = tempDir.resolve("ws");
        Path vaultRoot = wsRoot.resolve("vault");
        Files.createDirectories(vaultRoot.resolve("concepts")); // only concepts created

        WorkspaceService mockWorkspaceService = Mockito.mock(WorkspaceService.class);
        WorkspaceResponse mockResponse = new WorkspaceResponse(
                1L, "Workspace", wsRoot.toString(),
                wsRoot.resolve("inbox").toString(),
                wsRoot.resolve("archive").toString(),
                vaultRoot.toString(),
                wsRoot.resolve("data").toString(),
                wsRoot.resolve("config").toString(),
                "ACTIVE", "2026-08-28T00:00:00Z", "2026-08-28T00:00:00Z");

        when(mockWorkspaceService.findActiveWithoutValidation()).thenReturn(Optional.of(mockResponse));

        ActiveWorkspaceWikiPathResolver resolver =
                new ActiveWorkspaceWikiPathResolver(mockWorkspaceService, contract);

        assertThat(resolver.subDirectoryExists(WikiPageType.CONCEPT)).isTrue();
        assertThat(resolver.subDirectoryExists(WikiPageType.DECISION)).isFalse();
    }
}
