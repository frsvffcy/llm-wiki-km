package org.km.llmwiki.wiki;

import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Service that acts as the authoritative resolver for Wiki paths within the active workspace.
 *
 * <p>Callers in the publish-ready workflow (or preview/draft workflows) must obtain filesystem paths
 * exclusively through this service.  It guarantees that:
 * <ul>
 *   <li>Paths are resolved against the active workspace's trusted {@code vault/} root.</li>
 *   <li>Foreign or unauthenticated filesystem roots cannot be injected.</li>
 *   <li>The logical path conforms to the {@link WikiPathContract} and controlled {@link WikiPageType}.</li>
 *   <li>The resolved real-path does not escape the active workspace's vault boundary (symlink defense).</li>
 * </ul>
 *
 * <p>This service has <strong>no side effects</strong>: it does not create, modify, or delete any files.
 */
@Service
public class ActiveWorkspaceWikiPathResolver {

    private final WorkspaceService workspaceService;
    private final WikiPathContract wikiPathContract;

    public ActiveWorkspaceWikiPathResolver(WorkspaceService workspaceService, WikiPathContract wikiPathContract) {
        this.workspaceService = workspaceService;
        this.wikiPathContract = wikiPathContract;
    }

    /**
     * Resolves and validates a workspace-relative logical path against the active workspace.
     *
     * @param logicalRelativePath the workspace-relative path (e.g. {@code "vault/concepts/spring-boot-3.md"})
     * @return the resolved, boundary-verified absolute path in the active workspace's vault
     * @throws NoActiveWorkspaceException if no workspace is currently active
     * @throws WikiPathValidationException if the path violates lexical or boundary contracts
     */
    public Path resolveAndValidateRealPath(String logicalRelativePath) {
        WorkspaceResponse workspace = requireActiveWorkspace();
        Path vaultRoot = Path.of(workspace.vaultPath());
        return wikiPathContract.resolveAndValidateRealPath(vaultRoot, logicalRelativePath);
    }

    /**
     * Builds a logical relative path for the given page type and title, then resolves and validates
     * its real path against the active workspace.
     *
     * @param type  the controlled page type
     * @param title the page title
     * @return the resolved, boundary-verified absolute path in the active workspace's vault
     * @throws NoActiveWorkspaceException if no workspace is currently active
     * @throws WikiPathValidationException if the path violates lexical or boundary contracts
     */
    public Path resolveAndValidateRealPath(WikiPageType type, String title) {
        String logicalPath = wikiPathContract.resolveLogicalPath(type, title);
        return resolveAndValidateRealPath(logicalPath);
    }

    /**
     * Builds a validated, workspace-relative logical path for the given type and title.
     *
     * @param type  the controlled page type
     * @param title the page title
     * @return the logical relative path (e.g. {@code "vault/concepts/spring-boot-3.md"})
     */
    public String resolveLogicalPath(WikiPageType type, String title) {
        return wikiPathContract.resolveLogicalPath(type, title);
    }

    /**
     * Returns the canonical, boundary-verified {@code vault/} root of the currently active workspace.
     *
     * @return the real path of the active workspace's vault directory
     * @throws NoActiveWorkspaceException if no workspace is currently active
     * @throws IllegalStateException if the active workspace's vault path cannot be resolved
     */
    public Path getActiveVaultRoot() {
        WorkspaceResponse workspace = requireActiveWorkspace();
        try {
            return Path.of(workspace.vaultPath()).toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Could not resolve real path for active workspace vault: "
                    + workspace.vaultPath(), e);
        }
    }

    /**
     * Checks if the active workspace's vault has the sub-directory for the given page type.
     *
     * @param type the page type
     * @return {@code true} if the sub-directory exists in the active workspace
     */
    public boolean subDirectoryExists(WikiPageType type) {
        WorkspaceResponse workspace = requireActiveWorkspace();
        return wikiPathContract.subDirectoryExists(Path.of(workspace.vaultPath()), type);
    }

    private WorkspaceResponse requireActiveWorkspace() {
        return workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
    }
}
