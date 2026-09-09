package org.km.llmwiki.workspace;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the workspace layout validation projection: problem messages are an
 * operator-safe diagnostic that crosses the REST boundary via the workspace status response.
 * Hostile filesystem failures (paths, exception class names, secret-like content) must be
 * redacted by the shared policy, not passed through raw.
 */
@Tag("unit")
class WorkspaceLayoutValidatorTest {

    private final WorkspaceLayoutValidator validator = new WorkspaceLayoutValidator();

    @TempDir
    Path temp;

    @Test
    void validatesMissingDirectoriesWithoutChangingFilesystem() throws IOException {
        Path root = Files.createDirectories(temp.resolve("layout"));
        WorkspaceLayoutValidator.LayoutReport report = validator.validate(root);
        assertThat(report.valid()).isFalse();
        assertThat(report.problems()).containsExactlyInAnyOrder(
                "'inbox' directory does not exist",
                "'archive' directory does not exist",
                "'vault' directory does not exist",
                "'data' directory does not exist",
                "'config' directory does not exist",
                "'logs' directory does not exist",
                "'temp' directory does not exist");
        assertThat(report.repairedDirectories()).isEmpty();
        for (String directoryName : WorkspaceLayoutValidator.DIRECTORY_NAMES) {
            assertThat(root.resolve(directoryName)).doesNotExist();
        }
    }

    @Test
    void repairsMissingDirectoriesOnlyWhenExplicitlyRequested() throws IOException {
        Path root = Files.createDirectories(temp.resolve("repair"));
        WorkspaceLayoutValidator.LayoutReport report = validator.repair(root);
        assertThat(report.valid()).isTrue();
        assertThat(report.problems()).isEmpty();
        assertThat(report.repairedDirectories()).containsExactlyInAnyOrder(
                "inbox", "archive", "vault", "data", "config", "logs", "temp");
    }

    @Test
    void reportsMissingRootWithoutLeakingPathDetail() {
        Path root = temp.resolve("missing-root");
        WorkspaceLayoutValidator.LayoutReport report = validator.validate(root);
        assertThat(report.valid()).isFalse();
        assertThat(report.problems()).hasSize(1);
        assertThat(report.problems().getFirst()).startsWith("root directory does not exist");
        assertThat(report.problems().getFirst()).doesNotContain(root.toAbsolutePath().toString());
        assertThat(root).doesNotExist();
    }

    @Test
    void explicitRepairDoesNotCreateMissingRoot() {
        Path root = temp.resolve("missing-root-repair");

        WorkspaceLayoutValidator.LayoutReport report = validator.repair(root);

        assertThat(report.valid()).isFalse();
        assertThat(report.repairedDirectories()).isEmpty();
        assertThat(report.problems()).containsExactly("root directory does not exist");
        assertThat(root).doesNotExist();
    }

    @Test
    void reportsOrdinaryFileInRequiredDirectoryDeterministically() throws IOException {
        Path root = Files.createDirectories(temp.resolve("file-child"));
        Files.createFile(root.resolve("logs"));

        WorkspaceLayoutValidator.LayoutReport report = validator.validate(root);

        assertThat(report.valid()).isFalse();
        assertThat(report.problems()).contains("'logs' exists but is not a directory");
        assertThat(root.resolve("logs")).isRegularFile();
    }

    @Test
    void redactsHostileFilesystemFailureDetails() throws IOException {
        Path root = Files.createDirectories(temp.resolve("hostile"));
        Set<PosixFilePermission> readOnly = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(root, readOnly);
        WorkspaceLayoutValidator.LayoutReport report;
        try {
            report = validator.repair(root);
        } finally {
            Files.setPosixFilePermissions(root, EnumSet.allOf(PosixFilePermission.class));
        }
        assertThat(report.valid()).isFalse();
        List<String> problems = report.problems();
        assertThat(problems).isNotEmpty();
        assertThat(problems.getFirst())
                .startsWith("could not create directory 'inbox'")
                .contains("[REDACTED]")
                .doesNotContain(root.toAbsolutePath().toString(), "/Users", "toddyeh",
                        "AccessDenied");
    }
}
