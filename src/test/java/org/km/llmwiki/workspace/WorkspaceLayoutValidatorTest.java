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
    void repairsMissingDirectoriesAndStaysValid() throws IOException {
        Path root = Files.createDirectories(temp.resolve("layout"));
        WorkspaceLayoutValidator.LayoutReport report = validator.validateAndRepair(root);
        assertThat(report.valid()).isTrue();
        assertThat(report.problems()).isEmpty();
        assertThat(report.repairedDirectories()).containsExactlyInAnyOrder(
                "inbox", "archive", "vault", "data", "config", "logs", "temp");
    }

    @Test
    void reportsMissingRootWithoutLeakingPathDetail() {
        WorkspaceLayoutValidator.LayoutReport report = validator.validateAndRepair(temp.resolve("missing-root"));
        assertThat(report.valid()).isFalse();
        assertThat(report.problems()).hasSize(1);
        assertThat(report.problems().getFirst()).startsWith("root directory does not exist");
    }

    @Test
    void redactsHostileFilesystemFailureDetails() throws IOException {
        Path root = Files.createDirectories(temp.resolve("hostile"));
        Set<PosixFilePermission> readOnly = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(root, readOnly);
        WorkspaceLayoutValidator.LayoutReport report;
        try {
            report = validator.validateAndRepair(root);
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
