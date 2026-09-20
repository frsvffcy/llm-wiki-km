package org.km.llmwiki.release;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes the hermetic stale/current fixture matrix for the browser-first-mile
 * packaged gate (Refs #560) inside the fast tier.
 *
 * <p>The shell suite proves with real executions what static text guards cannot
 * prove: current-like packaged Browser resources PASS the shared content
 * implementation, stale #517 resources (missing {@code fetchList} helper or
 * {@code await refresh()} follow-ups) FAIL with a #517 contract message,
 * missing static resources fail closed, and a guard-less {@code refresh()}
 * fails closed. Static structure (single-sourced content library, delegation
 * call sites, no mtime selection) stays locked by
 * {@link ReleaseCandidateContractTest}.
 */
@Tag("contract")
class BrowserFirstMileSmokeShellContractTest {

    @Test
    void shellBrowserSmokeMatrixPasses() throws Exception {
        Path script = Path.of("scripts/tests/test-browser-first-mile-smoke.sh");
        assertThat(script).as("shell matrix must exist").isRegularFile();

        Process process = new ProcessBuilder("sh", script.toString())
                .directory(Path.of(System.getProperty("user.dir")).toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        // Bounded tail only: the full log stays out of the assertion message.
        String tail = output.length() <= 2000 ? output : output.substring(output.length() - 2000);
        assertThat(finished).as("shell matrix timed out; tail:\n%s", tail).isTrue();
        assertThat(process.exitValue()).as("shell matrix failed; tail:\n%s", tail).isZero();
        assertThat(output).contains("fail=0");
    }
}
