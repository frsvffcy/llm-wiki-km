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
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Privacy-safe daily-workflow validation v2 over a real loopback HTTP
 * listener (Refs #467 §B–§E).
 *
 * <p>Real HTTP socket via {@code DEFINED_PORT} on a per-class free loopback
 * port + {@link ProductAcceptanceHttpClient}; no MockMvc, no direct DB
 * inserts, no canonical vault writes. Clean temp knowledge root, fresh
 * workspace, synthetic corpus v2 only. Answer + embedding providers are served
 * by {@link DeterministicAcceptanceProviderStub} through the genuine
 * production OpenAI-compatible HTTP transport seam.
 *
 * <p>Built-JAR execution stays covered by
 * {@link ProductAcceptanceJarProcessIntegrationTest} on the same public
 * surface; this suite is the DEFINED_PORT real-HTTP evidence for the v2
 * procedure (never MockMvc-only).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class DailyWorkflowValidationV2IntegrationTest {

    private static final Path TEMP_ROOT = createTempRoot();
    private static final Path DB_PATH = TEMP_ROOT.resolve("data/knowledge.db");
    private static final DeterministicAcceptanceProviderStub STUB = createStub();
    private static final int ACCEPTANCE_PORT = freePort();

    @DynamicPropertySource
    static void acceptanceProperties(DynamicPropertyRegistry registry) {
        registry.add("app.persistence.sqlite.path", () -> DB_PATH.toString());
        registry.add("server.port", () -> String.valueOf(ACCEPTANCE_PORT));
        registry.add("app.deployment.forwarder-target",
                () -> "127.0.0.1:" + ACCEPTANCE_PORT);
        registry.add("app.ai.answer.enabled", () -> "true");
        registry.add("app.ai.answer.base-url", STUB::baseUrl);
        registry.add("app.ai.answer.model",
                () -> DeterministicAcceptanceProviderStub.MODEL);
        registry.add("app.ai.answer.api-key", () -> "acceptance-local-credential");
        registry.add("app.ai.embedding.enabled", () -> "true");
        registry.add("app.ai.embedding.base-url", STUB::baseUrl);
        registry.add("app.ai.embedding.model",
                () -> DeterministicAcceptanceProviderStub.MODEL);
        registry.add("app.ai.embedding.api-key", () -> "acceptance-local-credential");
        registry.add("app.graph.projection.enabled", () -> "false");
        registry.add("app.search.vector.enabled", () -> "false");
    }

    @AfterAll
    static void cleanup() {
        try {
            STUB.close();
        } catch (Exception ignored) {
            // Best-effort only.
        }
        deleteRecursively(TEMP_ROOT);
    }

    @Test
    void dailyWorkflowV2CompletesWithoutBlockerFindings() {
        Path workspaceRoot = TEMP_ROOT.resolve("ws");
        try {
            Files.createDirectories(workspaceRoot);
        } catch (Exception failure) {
            throw new IllegalStateException("workspace root", failure);
        }
        var client = new ProductAcceptanceHttpClient("http://127.0.0.1:" + ACCEPTANCE_PORT);
        var report = new DailyWorkflowFindingReport();
        report.executionMode("in-jvm-defined-port-daily-workflow-v2");
        report.prerequisiteNotes("clean temp root + fresh workspace; synthetic corpus v2 only; "
                + "deterministic loopback provider stub via production adapter seam; "
                + "Document Analysis via production offline fallback (stub/offline, bootstrap prompt, no harness write); "
                + "graph/vector disabled so the lexical baseline stays authoritative; "
                + "optional absence stays typed (semantic 503/graph 409) with lexical intact; "
                + "built-JAR path covered by ProductAcceptanceJarProcessIntegrationTest.");
        var harness = new DailyWorkflowHarnessV2(client, report, workspaceRoot);
        harness.runAll();

        assertThat(report.findings()).as("harness produced no findings").isNotEmpty();
        for (String journey : List.of("fresh-bootstrap", "workspace-bootstrap",
                "ingest-extract", "document-analysis", "structure-observable", "baseline-retrieval",
                "no-answer-probe", "governed-mutation", "source-revision",
                "degraded-contracts", "quality-boundary")) {
            assertThat(report.findings().stream()
                    .filter(finding -> finding.journey().equals(journey))
                    .findFirst())
                    .as("missing journey %s: %s", journey, report.findings())
                    .isPresent();
        }
        // No blocker finding may survive: any PRODUCT_BUG blocker forces NO-GO
        // and must become a corrective Issue instead of a candidate feature.
        assertThat(report.hasBlocker())
                .as("blocker findings: %s", report.findings())
                .isFalse();
        assertThat(report.overall()).isEqualTo("FULL-GO");
        // Typed classification only: every finding carries an explicit category,
        // never free text alone.
        assertThat(report.findings())
                .allSatisfy(finding -> assertThat(finding.category()).isNotNull());

        // The stub really served the production transport seam (not a service stub).
        assertThat(STUB.lastAnswerRequestBody()).contains("GROUNDED_ANSWER_PROMPT_V2");
        assertThat(STUB.lastAnswerAuthorization()).isEqualTo("Bearer acceptance-local-credential");

        report.writeTo(Path.of("target/daily-workflow-evidence/daily-workflow-v2"),
                gitSha(), System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("daily-workflow-v2-");
        } catch (Exception failure) {
            throw new IllegalStateException("temp daily-workflow root", failure);
        }
    }

    private static DeterministicAcceptanceProviderStub createStub() {
        try {
            return new DeterministicAcceptanceProviderStub();
        } catch (Exception failure) {
            throw new IllegalStateException("provider stub", failure);
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
                            // Best-effort cleanup only.
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
