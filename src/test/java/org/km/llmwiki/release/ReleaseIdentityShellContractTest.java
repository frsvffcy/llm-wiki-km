package org.km.llmwiki.release;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes the hermetic shell regression for the release identity helpers
 * (Refs #456 R2-R4, challenge cases 2-7; Refs #458 exact-resolver convergence)
 * inside the fast tier.
 *
 * <p>The shell suite covers what static text guards cannot prove: mtime
 * independence, absolute/relative path handling, swapped-artifact detection,
 * report/manifest source-commit cross-checks, multi-candidate ambiguity, and
 * the #458 exact candidate resolver plus sidecar-if-present verification.
 * Static structure (no {@code ls -t} selection, canonicalization call sites,
 * cross-check call sites) stays locked by {@link ReleaseCandidateContractTest}.
 */
@Tag("contract")
class ReleaseIdentityShellContractTest {

    @Test
    void shellIdentityRegressionPasses() throws Exception {
        Path script = Path.of("scripts/tests/test-release-identity.sh");
        assertThat(script).as("shell regression must exist").isRegularFile();

        Process process = new ProcessBuilder("sh", script.toString())
                .directory(Path.of(System.getProperty("user.dir")).toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        // Bounded tail only: the full log stays out of the assertion message.
        String tail = output.length() <= 2000 ? output : output.substring(output.length() - 2000);
        assertThat(finished).as("shell regression timed out; tail:\n%s", tail).isTrue();
        assertThat(process.exitValue()).as("shell regression failed; tail:\n%s", tail).isZero();
        assertThat(output).contains("fail=0");
    }
}
