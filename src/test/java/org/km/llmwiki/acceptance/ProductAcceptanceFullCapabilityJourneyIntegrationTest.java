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
 * Deterministic full-capability product journey over a real loopback HTTP
 * listener (Refs #429 §D–§G).
 *
 * <p>Answer + embedding providers are served by
 * {@link DeterministicAcceptanceProviderStub} through the genuine production
 * OpenAI-compatible HTTP transport seam (base-url points at the stub; the real
 * {@code JdkOpenAiCompatible*Transport} runs). Graph projection is enabled on a
 * temp path. Query transformation stays at its disabled default; vector KNN
 * stays disabled (no native prerequisite) and is asserted as a typed SKIP that
 * blocks release FULL-GO rather than a fake green.
 *
 * <p>No MockMvc, no direct DB inserts, no vault writes as flow shortcuts.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ProductAcceptanceFullCapabilityJourneyIntegrationTest {

    private static final Path TEMP_ROOT = createTempRoot();
    private static final Path DB_PATH = TEMP_ROOT.resolve("data/knowledge.db");
    private static final Path GRAPH_PATH = TEMP_ROOT.resolve("graph");
    private static final DeterministicAcceptanceProviderStub STUB = createStub();
    // See the baseline test: RANDOM_PORT violates the deployment fail-fast
    // contract, so bind one real loopback port with a matching forwarder target.
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
        // #435: mirror the JarProcess glue — when the parent environment provisions
        // a readable pinned sqlite-vec native, enable vector so
        // vector-prerequisite can PASS (release FULL-GO); otherwise force disabled
        // so a developer-inherited ENABLED=true never leaks in and the typed SKIP
        // (blocks FULL-GO, never fake-green) is preserved. No change to #429
        // corpus/harness verdict semantics.
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
    void deterministicFullCapabilityCompletesGovernanceWithTypedVectorPrerequisite() {
        Path workspaceRoot = TEMP_ROOT.resolve("ws");
        try {
            Files.createDirectories(workspaceRoot);
        } catch (Exception failure) {
            throw new IllegalStateException("workspace root", failure);
        }
        var client = new ProductAcceptanceHttpClient("http://127.0.0.1:" + ACCEPTANCE_PORT);
        var report = new ProductAcceptanceReport();
        report.executionMode("in-jvm-defined-port-full-capability");
        if (isVectorNativeProvisioned()) {
            report.prerequisiteNotes("deterministic loopback provider stub via production "
                    + "adapter seam; graph enabled on temp path; pinned sqlite-vec native "
                    + "provisioned so semantic KNN executes (release FULL-GO path).");
        } else {
            report.prerequisiteNotes("deterministic loopback provider stub via production "
                    + "adapter seam; graph enabled on temp path; sqlite-vec native absent "
                    + "so semantic KNN stays typed SKIP (blocks release FULL-GO).");
        }
        var harness = new ProductAcceptanceHarness(client, report, workspaceRoot);
        harness.runAll(true, true);

        assertThat(report.steps()).as("harness produced no steps").isNotEmpty();
        for (String stepId : new String[]{"clean-startup", "workspace", "ingest-extract",
                "document-analysis", "baseline-retrieval", "embedding-projection", "governed-mutation",
                "quality-boundary", "graph-boundary"}) {
            assertThat(verdictOf(report, stepId)).as("step %s: %s", stepId, report.steps())
                    .isEqualTo(ProductAcceptanceReport.Verdict.PASS);
        }
        // Vector KNN has no native prerequisite here: typed SKIP, never fake PASS.
        // #435: when pinned native is provisioned the same step must PASS (FULL-GO
        // path); otherwise it stays SKIP and blocks release FULL-GO.
        if (isVectorNativeProvisioned()) {
            assertThat(verdictOf(report, "vector-prerequisite"))
                    .as("step vector-prerequisite: %s", report.steps())
                    .isEqualTo(ProductAcceptanceReport.Verdict.PASS);
        } else {
            assertThat(verdictOf(report, "vector-prerequisite"))
                    .as("step vector-prerequisite: %s", report.steps())
                    .isEqualTo(ProductAcceptanceReport.Verdict.SKIP);
        }

        // The stub really served the production transport seam (not a service stub).
        assertThat(STUB.lastAnswerRequestBody()).contains("GROUNDED_ANSWER_PROMPT_V2");
        assertThat(STUB.lastEmbeddingRequestBody()).contains("input");
        assertThat(STUB.lastAnswerAuthorization()).isEqualTo("Bearer " + "acceptance-local-credential");

        report.writeTo(Path.of("target/release-evidence/acceptance-full-capability"),
                gitSha(), System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    private static ProductAcceptanceReport.Verdict verdictOf(ProductAcceptanceReport report,
                                                             String id) {
        return report.steps().stream().filter(step -> step.id().equals(id)).findFirst()
                .map(ProductAcceptanceReport.Step::verdict).orElse(null);
    }

    /**
     * #435 glue: pinned sqlite-vec native is provisioned only when the parent
     * environment provides a readable {@code VECTOR_EXTENSION_PATH}. Absent or
     * unreadable stays provider-free SKIP (never fake-green); no floating
     * download, no second version truth (pinned v0.1.9 via native matrix).
     */
    private static boolean isVectorNativeProvisioned() {
        String path = System.getenv("VECTOR_EXTENSION_PATH");
        return path != null && !path.isBlank() && Files.isRegularFile(Path.of(path));
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("product-acceptance-full-");
        } catch (Exception failure) {
            throw new IllegalStateException("temp acceptance root", failure);
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
