package org.km.llmwiki.web.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class OwnerPasswordVerifierToolTest {

    private record Result(int exit, String stdout, String stderr) {
    }

    private Result run(String stdin, String... args) {
        ByteArrayInputStream in =
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int exit = OwnerPasswordVerifierTool.generate(
                in,
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8),
                args,
                false);
        return new Result(
                exit,
                outBytes.toString(StandardCharsets.UTF_8),
                errBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void stdinPasswordProducesAVerifiableVerifier() {
        Result result = run("operator-chosen-password\n",
                "--iterations", String.valueOf(OwnerPasswordVerifier.MIN_ITERATIONS));

        assertThat(result.exit()).isZero();
        String verifier = result.stdout().strip();
        assertThat(OwnerPasswordVerifier.verify("operator-chosen-password", verifier)).isTrue();
        assertThat(OwnerPasswordVerifier.verify("wrong", verifier)).isFalse();
        // stdout carries only the verifier; guidance stays on stderr.
        assertThat(result.stdout().strip()).doesNotContain(" ");
        assertThat(result.stderr()).doesNotContain("operator-chosen-password");
        assertThat(result.stdout()).doesNotContain("operator-chosen-password");
    }

    @Test
    void emptyPasswordFailsClosed() {
        Result result = run("\n");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void invalidIterationsFailClosed() {
        Result missing = run("password\n", "--iterations");

        assertThat(missing.exit()).isEqualTo(2);

        Result tooSmall = run("password\n", "--iterations", "1000");

        assertThat(tooSmall.exit()).isEqualTo(2);
        assertThat(tooSmall.stdout()).isEmpty();

        Result unknown = run("password\n", "--password", "password");

        assertThat(unknown.exit()).isEqualTo(2);
        assertThat(unknown.stdout()).isEmpty();
    }
}
