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
 * Full-capability daily-workflow validation v2 over a real loopback HTTP
 * listener (Refs #472 §C).
 *
 * <p>Same synthetic corpus/procedure as the baseline V2 suite, but with graph
 * enabled and vector conditional on the pinned sqlite-vec native. Answer +
 * embedding providers are served by {@link DeterministicAcceptanceProviderStub}
 * through the genuine production OpenAI-compatible HTTP transport seam.
 * Production defaults are never changed by the harness; this suite only
 * enables optional capabilities via test properties.
 *
 * <p>Daily-workflow evidence must explicitly record which modes were actually
 * executed (HYBRID_FTS baseline plus semantic and vector and graph modes);
 * it must never cite an unrelated suite as proof. When the native
 * prerequisite is absent the semantic journey records a typed unavailable
 * outcome (never a fake PASS) and the Sprint must not claim full-capability
 * FULL-GO from that run.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class DailyWorkflowFullCapabilityV2IntegrationTest {

    private static final Path TEMP_ROOT = createTempRoot();
    private static final Path DB_PATH = TEMP_ROOT.resolve("data/knowledge.db");
    private static final Path GRAPH_PATH = TEMP_ROOT.resolve("graph");
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
        registry.add("app.graph.projection.enabled", () -> "true");
        registry.add("app.graph.projection.path", () -> GRAPH_PATH.toString());
        if (isVectorNativeProvisioned()) {
            registry.add("app.search.vector.enabled", () -> "true");
            registry.add("app.search.vector.extension-path",
                    () -> System.getenv("VECTOR_EXTENSION_PATH"));
        } else {
            registry.add("app.search.vector.enabled", () -> "false");
        }
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
    void dailyWorkflowFullCapabilityProvesSemanticVectorAndGraphJourneys() {
        Path workspaceRoot = TEMP_ROOT.resolve("ws");
        try {
            Files.createDirectories(workspaceRoot);
        } catch (Exception failure) {
            throw new IllegalStateException("workspace root", failure);
        }
        var client = new ProductAcceptanceHttpClient("http://127.0.0.1:" + ACCEPTANCE_PORT);
        var report = new DailyWorkflowFindingReport();
        report.executionMode("in-jvm-defined-port-daily-workflow-v2-full-capability");
        if (isVectorNativeProvisioned()) {
            report.prerequisiteNotes("clean temp root + fresh workspace; synthetic corpus v2 only; "
                    + "deterministic loopback provider stub via production adapter seam; "
                    + "Document Analysis via offline fallback (stub/offline); "
                    + "graph enabled on temp path; pinned sqlite-vec native provisioned "
                    + "(v0.1.9, checksum + JDBC smoke) so SEMANTIC_*/HYBRID_VECTOR execute; "
                    + "executed modes: HYBRID_FTS + SEMANTIC_SOURCE/WIKI + HYBRID_VECTOR + HYBRID_GRAPH.");
        } else {
            report.prerequisiteNotes("clean temp root + fresh workspace; synthetic corpus v2 only; "
                    + "deterministic loopback provider stub via production adapter seam; "
                    + "Document Analysis via offline fallback (stub/offline); "
                    + "graph enabled on temp path; sqlite-vec native absent so semantic KNN stays "
                    + "typed unavailable (this run does NOT prove HYBRID_VECTOR PASS; Sprint "
                    + "full-capability FULL-GO requires a native-provisioned run); "
                    + "executed modes: HYBRID_FTS + HYBRID_GRAPH (SEMANTIC_*/HYBRID_VECTOR typed unavailable).");
        }
        var harness = new DailyWorkflowHarnessV2(client, report, workspaceRoot);
        harness.runFullCapability();

        assertThat(report.findings()).as("harness produced no findings").isNotEmpty();
        for (String journey : List.of("fresh-bootstrap", "workspace-bootstrap",
                "ingest-extract", "document-analysis", "structure-observable", "baseline-retrieval",
                "no-answer-probe", "governed-mutation", "source-revision",
                "embedding-projection", "semantic-vector-retrieval",
                "graph-boundary", "hybrid-graph-retrieval", "quality-boundary")) {
            assertThat(report.findings().stream()
                    .filter(finding -> finding.journey().equals(journey))
                    .findFirst())
                    .as("missing journey %s: %s", journey, report.findings())
                    .isPresent();
        }
        assertThat(report.hasBlocker())
                .as("blocker findings: %s", report.findings())
                .isFalse();
        assertThat(report.overall()).isEqualTo("FULL-GO");
        assertThat(report.findings())
                .allSatisfy(finding -> assertThat(finding.category()).isNotNull());

        // Full-capability must prove it actually executed the claimed modes,
        // not merely cite another suite. When native is present the semantic
        // journey must be executed (not typed unavailable); when absent it
        // must explicitly record the typed prerequisite outcome (never fake
        // PASS) and the Sprint must not claim full vector PASS from that run.
        var semantic = report.findings().stream()
                .filter(finding -> finding.journey().equals("semantic-vector-retrieval"))
                .findFirst().orElseThrow();
        if (isVectorNativeProvisioned()) {
            assertThat(semantic.category())
                    .as("semantic journey with native: %s", semantic)
                    .isEqualTo(DailyWorkflowFindingReport.Category.RETRIEVAL_QUALITY);
            assertThat(semantic.observed()).doesNotContain("typed 503");
        } else {
            assertThat(semantic.category())
                    .as("semantic journey without native: %s", semantic)
                    .isEqualTo(DailyWorkflowFindingReport.Category.NO_EVIDENCE);
            assertThat(semantic.observed()).as("semantic journey without native: %s", semantic)
                    .contains("typed 503");
        }

        // Embedding never needs the native KNN prerequisite (projection via
        // the deterministic provider seam), so it must always execute here.
        var embedding = report.findings().stream()
                .filter(finding -> finding.journey().equals("embedding-projection"))
                .findFirst().orElseThrow();
        assertThat(embedding.category())
                .as("embedding journey: %s", embedding)
                .isEqualTo(DailyWorkflowFindingReport.Category.RETRIEVAL_QUALITY);

        // Graph is enabled in this profile (no native prerequisite), so both
        // graph journeys must have executed, never typed unavailable.
        var graph = report.findings().stream()
                .filter(finding -> finding.journey().equals("graph-boundary"))
                .findFirst().orElseThrow();
        assertThat(graph.category())
                .as("graph journey: %s", graph)
                .isEqualTo(DailyWorkflowFindingReport.Category.OPERABILITY);
        var hybridGraph = report.findings().stream()
                .filter(finding -> finding.journey().equals("hybrid-graph-retrieval"))
                .findFirst().orElseThrow();
        assertThat(hybridGraph.category())
                .as("hybrid-graph journey: %s", hybridGraph)
                .isEqualTo(DailyWorkflowFindingReport.Category.RETRIEVAL_QUALITY);

        assertThat(STUB.lastAnswerRequestBody()).contains("GROUNDED_ANSWER_PROMPT_V2");
        assertThat(STUB.lastAnswerAuthorization()).isEqualTo("Bearer acceptance-local-credential");
        assertThat(STUB.lastEmbeddingRequestBody()).contains("input");

        report.writeTo(Path.of("target/daily-workflow-evidence/daily-workflow-v2-full-capability"),
                gitSha(), System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    private static boolean isVectorNativeProvisioned() {
        String path = System.getenv("VECTOR_EXTENSION_PATH");
        return path != null && !path.isBlank() && Files.isRegularFile(Path.of(path));
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("daily-workflow-v2-full-");
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
