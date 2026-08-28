package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration-style tests for {@link WikiPathContract#resolveAndValidateRealPath}
 * that exercise real filesystem operations (TempDir, symlinks) without needing
 * Spring Boot or SQLite.
 *
 * <p>These tests verify the vault boundary invariant:
 * <ul>
 *   <li>A valid path inside the vault sub-directory resolves correctly.</li>
 *   <li>A symlink whose target points outside the vault root is rejected.</li>
 *   <li>The operation has <strong>no side effects</strong>: no files are created
 *       in the target directory and the vault tree remains untouched.</li>
 * </ul>
 */
class WikiVaultBoundaryIntegrationTest {

    private final WikiPathContract contract = new WikiPathContract();

    // -----------------------------------------------------------------------
    // Happy path — valid path resolves inside vault
    // -----------------------------------------------------------------------

    @Test
    void resolvesValidPathInsideVault(@TempDir Path workspaceRoot) throws IOException {
        Path vaultRoot = workspaceRoot.resolve("vault");
        Path conceptsDir = vaultRoot.resolve("concepts");
        Files.createDirectories(conceptsDir);

        Path result = contract.resolveAndValidateRealPath(vaultRoot, "vault/concepts/spring-boot-3.md");

        // The parent directory must be vault/concepts/ — use getParent() to avoid calling
        // toRealPath() on the (non-existent) file itself, which would throw NoSuchFileException.
        assertThat(result.getParent()).isEqualTo(conceptsDir.toRealPath());
        assertThat(result.getFileName().toString()).isEqualTo("spring-boot-3.md");

        // No side effect: the file must not have been created
        assertThat(Files.exists(result)).isFalse();
    }

    @Test
    void resolvesAllPageTypeSubdirectoriesWhenTheyExist(@TempDir Path workspaceRoot) throws IOException {
        Path vaultRoot = workspaceRoot.resolve("vault");
        for (WikiPageType type : WikiPageType.values()) {
            Files.createDirectories(vaultRoot.resolve(type.folderName()));
        }

        for (WikiPageType type : WikiPageType.values()) {
            String logicalPath = contract.resolveLogicalPath(type, "Test Page");
            Path resolved = contract.resolveAndValidateRealPath(vaultRoot, logicalPath);
            assertThat(resolved.toString()).contains(type.folderName());
        }
    }

    // -----------------------------------------------------------------------
    // Symlink escape — symlink target outside vault should be rejected
    // -----------------------------------------------------------------------

    @Test
    void rejectsSymlinkEscapingVaultBoundary(@TempDir Path workspaceRoot) throws IOException {
        Path vaultRoot = workspaceRoot.resolve("vault");
        Path conceptsDir = vaultRoot.resolve("concepts");
        Files.createDirectories(conceptsDir);

        // Create a directory outside the vault that we'll symlink to
        Path outsideTarget = workspaceRoot.resolve("outside-secrets");
        Files.createDirectories(outsideTarget);

        // Create a symlink inside vault/concepts/ pointing to the outside directory
        Path symlinkDir = conceptsDir.resolve("escaped");
        Files.createSymbolicLink(symlinkDir, outsideTarget);

        // Attempt to resolve a path through the symlink
        assertThatThrownBy(() ->
                contract.resolveAndValidateRealPath(vaultRoot, "vault/concepts/escaped/secret.md"))
                .isInstanceOf(WikiPathValidationException.class)
                .satisfies(ex -> {
                    WikiPathValidationException.Reason reason = ((WikiPathValidationException) ex).reason();
                    assertThat(reason).isIn(
                            WikiPathValidationException.Reason.SYMLINK_ESCAPE,
                            WikiPathValidationException.Reason.OUTSIDE_VAULT_BOUNDARY);
                });
    }

    // -----------------------------------------------------------------------
    // Path traversal — lexical check fires before filesystem access
    // -----------------------------------------------------------------------

    @Test
    void rejectsPathTraversalBeforeFilesystemAccess(@TempDir Path workspaceRoot) throws IOException {
        Path vaultRoot = workspaceRoot.resolve("vault");
        Files.createDirectories(vaultRoot);

        assertThatThrownBy(() ->
                contract.resolveAndValidateRealPath(vaultRoot, "vault/../archive/secret.pdf"))
                .isInstanceOf(WikiPathValidationException.class)
                .satisfies(ex -> assertThat(((WikiPathValidationException) ex).reason())
                        .isEqualTo(WikiPathValidationException.Reason.PATH_TRAVERSAL_DETECTED));
    }

    // -----------------------------------------------------------------------
    // No side effects — vault tree is never modified
    // -----------------------------------------------------------------------

    @Test
    void doesNotCreateAnyFilesOrDirectories(@TempDir Path workspaceRoot) throws IOException {
        Path vaultRoot = workspaceRoot.resolve("vault");
        Path conceptsDir = vaultRoot.resolve("concepts");
        Files.createDirectories(conceptsDir);

        long beforeCount = Files.walk(workspaceRoot).count();

        contract.resolveAndValidateRealPath(vaultRoot, "vault/concepts/my-page.md");

        long afterCount = Files.walk(workspaceRoot).count();
        assertThat(afterCount).isEqualTo(beforeCount);
    }

    // -----------------------------------------------------------------------
    // archive/ and inbox/ must not be reachable via this contract
    // -----------------------------------------------------------------------

    @Test
    void doesNotAllowPathsTargetingArchiveDirectory(@TempDir Path workspaceRoot) {
        Path vaultRoot = workspaceRoot.resolve("vault");

        assertThatThrownBy(() ->
                contract.resolveAndValidateRealPath(vaultRoot, "archive/secret.pdf"))
                .isInstanceOf(WikiPathValidationException.class)
                .satisfies(ex -> assertThat(((WikiPathValidationException) ex).reason())
                        .isEqualTo(WikiPathValidationException.Reason.OUTSIDE_VAULT_BOUNDARY));
    }
}
