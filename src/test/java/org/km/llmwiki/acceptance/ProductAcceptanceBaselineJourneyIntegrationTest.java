package org.km.llmwiki.acceptance;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOCAL_ONLY baseline product journey over a real loopback HTTP listener
 * (Refs #429 §B–§C, §F).
 *
 * <p>Real HTTP socket via {@code DEFINED_PORT} on a per-class free loopback port
 * + {@link ProductAcceptanceHttpClient}; no MockMvc. Clean temp knowledge root (temp SQLite path), production defaults
 * (owner auth off, answer/embedding/graph disabled, query transformation
 * disabled). Optional-provider absence must stay typed, never fatal.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ProductAcceptanceBaselineJourneyIntegrationTest {

    private static final Path TEMP_ROOT = createTempRoot();
    private static final Path DB_PATH = TEMP_ROOT.resolve("data/knowledge.db");
    // RANDOM_PORT (server.port=0) is rejected by the deployment fail-fast validator
    // and breaks the forwarder-target contract, so the acceptance instance binds a
    // real loopback port chosen once per class with a matching forwarder target.
    private static final int ACCEPTANCE_PORT = freePort();

    @DynamicPropertySource
    static void acceptanceProperties(DynamicPropertyRegistry registry) {
        registry.add("app.persistence.sqlite.path", () -> DB_PATH.toString());
        registry.add("server.port", () -> String.valueOf(ACCEPTANCE_PORT));
        registry.add("app.deployment.forwarder-target",
                () -> "127.0.0.1:" + ACCEPTANCE_PORT);
    }

    @AfterAll
    static void cleanup() {
        deleteRecursively(TEMP_ROOT);
    }

    @Test
    void localOnlyBaselineCompletesWorkspaceToQualityWithTypedProviderAbsence() throws Exception {
        Path workspaceRoot = TEMP_ROOT.resolve("ws");
        Files.createDirectories(workspaceRoot);
        var client = new ProductAcceptanceHttpClient("http://127.0.0.1:" + ACCEPTANCE_PORT);
        var report = new ProductAcceptanceReport();
        report.executionMode("in-jvm-defined-port-baseline");
        report.prerequisiteNotes("LOCAL_ONLY defaults; answer/embedding/graph disabled; "
                + "sqlite-vec native not required for baseline.");
        var harness = new ProductAcceptanceHarness(client, report, workspaceRoot);
        harness.runAll(false, false);

        assertThat(report.steps()).isNotEmpty();
        assertThat(verdictOf(report, "clean-startup"))
                .isEqualTo(ProductAcceptanceReport.Verdict.PASS);
        assertThat(verdictOf(report, "workspace"))
                .isEqualTo(ProductAcceptanceReport.Verdict.PASS);
        assertThat(verdictOf(report, "ingest-extract"))
                .isEqualTo(ProductAcceptanceReport.Verdict.PASS);
        // #454 §B: fresh-workspace Document Analysis blocking journey runs even
        // in LOCAL_ONLY baseline via the production offline fallback (stub/offline).
        assertThat(verdictOf(report, "document-analysis"))
                .isEqualTo(ProductAcceptanceReport.Verdict.PASS);
        assertThat(verdictOf(report, "baseline-retrieval"))
                .isEqualTo(ProductAcceptanceReport.Verdict.PASS);
        assertThat(verdictOf(report, "ask-provider-disabled-typed"))
                .isEqualTo(ProductAcceptanceReport.Verdict.PASS);
        assertThat(verdictOf(report, "ask-read-only-precondition"))
                .isEqualTo(ProductAcceptanceReport.Verdict.PASS);
        assertThat(verdictOf(report, "quality-boundary"))
                .isEqualTo(ProductAcceptanceReport.Verdict.PASS);
        // Governed mutation + graph + vector-semantic are typed SKIP in this profile
        // by design (covered by the full-capability journey); SKIPs block release
        // FULL-GO downstream but this harness execution itself is correct.
        assertThat(verdictOf(report, "governed-mutation"))
                .isEqualTo(ProductAcceptanceReport.Verdict.SKIP);

        report.writeTo(Path.of("target/release-evidence/acceptance-baseline"),
                gitSha(), System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    private static ProductAcceptanceReport.Verdict verdictOf(ProductAcceptanceReport report,
                                                             String id) {
        return report.steps().stream().filter(step -> step.id().equals(id)).findFirst()
                .map(ProductAcceptanceReport.Step::verdict).orElse(null);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("product-acceptance-baseline-");
        } catch (Exception failure) {
            throw new IllegalStateException("temp acceptance root", failure);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception failure) {
            throw new IllegalStateException("free loopback port", failure);
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // Best-effort cleanup; never fail the test on temp removal.
                        }
                    });
        } catch (Exception ignored) {
            // Best-effort cleanup only.
        }
    }

    private static String gitSha() {
        try {
            var process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            return output.isBlank() ? "unavailable" : output;
        } catch (Exception failure) {
            return "unavailable";
        }
    }
}
