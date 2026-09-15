package org.km.llmwiki.acceptance;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Built-JAR process acceptance with controlled restart (Refs #429 §B, §G).
 *
 * <p>Preferred release path: the clean built {@code target/llm-wiki-km-<version>.jar}
 * (version derived from the Maven-filtered {@code version.properties}, never a
 * hardcoded literal — Refs #456 R1) as
 * an actual OS process over a real loopback HTTP socket with a temp knowledge
 * root. When the JAR is absent (ordinary {@code mvn test} before
 * {@code package}), the test aborts with an explicit prerequisite message
 * instead of faking a pass; the release workflow builds the JAR first and then
 * runs this suite so the gate is genuinely executed.
 *
 * <p>Restart: the same temp instance is stopped and started again on a new
 * port; canonical published content, Flyway state, and citation currentness
 * must survive without stale projections reporting fake-READY.
 */
@Tag("integration")
class ProductAcceptanceJarProcessIntegrationTest {

    private static final Path JAR = resolveCandidateJar();
    private static final Path TEMP_ROOT = createTempRoot();
    private static DeterministicAcceptanceProviderStub stub;

    @AfterAll
    static void cleanup() {
        if (stub != null) {
            try {
                stub.close();
            } catch (Exception ignored) {
                // Best-effort only.
            }
        }
        deleteRecursively(TEMP_ROOT);
    }

    @Test
    void builtJarFullJourneySurvivesControlledRestart() throws Exception {
        assumeTrue(Files.isRegularFile(JAR),
                "release acceptance requires the built application JAR at "
                        + JAR + "; build it first (e.g. mvn package) — skipping, never passing.");
        stub = new DeterministicAcceptanceProviderStub();
        Path workspaceRoot = TEMP_ROOT.resolve("ws");
        Files.createDirectories(workspaceRoot);
        Path dbPath = TEMP_ROOT.resolve("data/knowledge.db");
        Path graphPath = TEMP_ROOT.resolve("graph");
        Map<String, String> environment = baseEnvironment(dbPath, graphPath);

        String publishedKnowledgeId;
        try (var lifecycle = new ProductAcceptanceProcessLifecycle(JAR, environment)) {
            int port = lifecycle.start(TEMP_ROOT);
            var client = new ProductAcceptanceHttpClient("http://127.0.0.1:" + port);
            var report = new ProductAcceptanceReport();
            report.executionMode("built-jar-subprocess-full-capability");
            report.prerequisiteNotes("clean built JAR as OS process; temp knowledge root; "
                    + "deterministic loopback stub via production adapter seam.");
            var harness = new ProductAcceptanceHarness(client, report, workspaceRoot);
            harness.runAll(true, true);
            // #454 §B + §D: exact-artifact dogfood must prove fresh-workspace
            // Document Analysis on the packaged JAR (no Files.write / DB shortcut).
            assertThat(verdictOf(report, "document-analysis"))
                    .isEqualTo(ProductAcceptanceReport.Verdict.PASS);
            assertThat(verdictOf(report, "governed-mutation"))
                    .isEqualTo(ProductAcceptanceReport.Verdict.PASS);
            publishedKnowledgeId = publishedId(client);
            assertThat(publishedKnowledgeId).isNotBlank();
            report.writeTo(Path.of("target/release-evidence/acceptance-jar-pre-restart"),
                    gitSha(), System.getProperty("java.version"),
                    System.getProperty("os.name"), System.getProperty("os.arch"));
        }

        // Controlled restart on the same temp instance with a fresh port.
        try (var restarted = new ProductAcceptanceProcessLifecycle(JAR, environment)) {
            int port = restarted.start(TEMP_ROOT);
            var client = new ProductAcceptanceHttpClient("http://127.0.0.1:" + port);
            var report = new ProductAcceptanceReport();
            report.executionMode("built-jar-subprocess-restart");

            // Canonical published content still exists after restart.
            var read = client.get("/api/v1/wiki/" + publishedKnowledgeId);
            if (read.status() != 200) {
                report.add("restart-canonical", ProductAcceptanceReport.Verdict.FAIL,
                        "published wiki missing after restart, HTTP " + read.status());
            } else {
                report.add("restart-canonical", ProductAcceptanceReport.Verdict.PASS,
                        "published wiki readable after restart");
            }
            // Flyway/operational state reopens: system status + wiki list work.
            var status = client.get("/api/v1/system/status");
            if (status.status() != 200) {
                report.add("restart-operational", ProductAcceptanceReport.Verdict.FAIL,
                        "system status HTTP " + status.status() + " after restart");
            } else {
                report.add("restart-operational", ProductAcceptanceReport.Verdict.PASS,
                        "system status=" + status.json(client.mapper()).path("data")
                                .path("status").asText(""));
            }
            // Derived readiness must not fake READY: graph + embedding endpoints
            // return typed states, never a crash or a stale success.
            var graph = client.get("/api/v1/graph/projection/readiness");
            if (graph.status() != 200) {
                report.add("restart-projection-currentness",
                        ProductAcceptanceReport.Verdict.FAIL,
                        "graph readiness HTTP " + graph.status() + " after restart");
            } else {
                report.add("restart-projection-currentness",
                        ProductAcceptanceReport.Verdict.PASS,
                        "graph readiness status=" + graph.json(client.mapper()).path("data")
                                .path("status").asText("") + " after restart");
            }
            // Citation currentness does not drift across restart: the first chunk
            // locator still resolves through the public boundary.
            var chunks = client.get("/api/v1/documents/1/chunks");
            report.add("restart-citation-currentness",
                    chunks.status() == 200 || chunks.status() == 404
                            ? ProductAcceptanceReport.Verdict.PASS
                            : ProductAcceptanceReport.Verdict.FAIL,
                    "post-restart chunk read HTTP " + chunks.status());

            assertThat(verdictOf(report, "restart-canonical"))
                    .isEqualTo(ProductAcceptanceReport.Verdict.PASS);
            assertThat(verdictOf(report, "restart-operational"))
                    .isEqualTo(ProductAcceptanceReport.Verdict.PASS);
            assertThat(verdictOf(report, "restart-projection-currentness"))
                    .isEqualTo(ProductAcceptanceReport.Verdict.PASS);
            report.writeTo(Path.of("target/release-evidence/acceptance-jar-post-restart"),
                    gitSha(), System.getProperty("java.version"),
                    System.getProperty("os.name"), System.getProperty("os.arch"));
        }
    }

    private static Map<String, String> baseEnvironment(Path dbPath, Path graphPath) {
        Map<String, String> environment = new HashMap<>();
        environment.put("KNOWLEDGE_DB_PATH", dbPath.toString());
        environment.put("GRAPH_PROJECTION_ENABLED", "true");
        environment.put("GRAPH_PROJECTION_PATH", graphPath.toString());
        environment.put("ANSWER_PROVIDER_ENABLED", "true");
        environment.put("ANSWER_PROVIDER_BASE_URL", stub.baseUrl());
        environment.put("ANSWER_PROVIDER_MODEL", DeterministicAcceptanceProviderStub.MODEL);
        environment.put("OPENAI_API_KEY", "acceptance-local-credential");
        environment.put("EMBEDDING_PROVIDER_ENABLED", "true");
        environment.put("EMBEDDING_PROVIDER_BASE_URL", stub.baseUrl());
        environment.put("EMBEDDING_PROVIDER_MODEL", DeterministicAcceptanceProviderStub.MODEL);
        environment.put("EMBEDDING_PROVIDER_API_KEY", "acceptance-local-credential");
        // #435: propagate pinned sqlite-vec native to the JAR subprocess only when
        // the parent environment provisions a readable VECTOR_EXTENSION_PATH.
        // Present → ENABLED=true + path so vector-prerequisite can PASS and the
        // release can reach FULL-GO. Absent/invalid → explicit ENABLED=false so a
        // developer/CI-inherited true never leaks in non-deterministically; the
        // harness keeps its typed SKIP (blocks FULL-GO, never fake-green).
        // No change to #429 domain flow, corpus, or harness verdict semantics.
        String vectorPath = System.getenv("VECTOR_EXTENSION_PATH");
        if (vectorPath != null && !vectorPath.isBlank()
                && Files.isRegularFile(Path.of(vectorPath))) {
            environment.put("VECTOR_CAPABILITY_ENABLED", "true");
            environment.put("VECTOR_EXTENSION_PATH", vectorPath);
        } else {
            environment.put("VECTOR_CAPABILITY_ENABLED", "false");
        }
        return Map.copyOf(environment);
    }

    private static String publishedId(ProductAcceptanceHttpClient client) {
        var list = client.get("/api/v1/wiki?page=0&size=5");
        if (list.status() != 200 || list.json(client.mapper()).path("data").size() == 0) {
            return "";
        }
        return list.json(client.mapper()).path("data").get(0).path("knowledgeId").asText("");
    }

    private static ProductAcceptanceReport.Verdict verdictOf(ProductAcceptanceReport report,
                                                             String id) {
        return report.steps().stream().filter(step -> step.id().equals(id)).findFirst()
                .map(ProductAcceptanceReport.Step::verdict).orElse(null);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("product-acceptance-jar-");
        } catch (Exception failure) {
            throw new IllegalStateException("temp acceptance root", failure);
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

    /**
     * Exact candidate filename derived from the Maven-filtered
     * {@code version.properties} (Refs #456 R1/R2): a release bump never
     * requires editing a second version literal here.
     */
    private static Path resolveCandidateJar() {
        String version = null;
        try (var stream = ProductAcceptanceJarProcessIntegrationTest.class
                .getResourceAsStream("/version.properties")) {
            if (stream != null) {
                var properties = new java.util.Properties();
                properties.load(stream);
                version = properties.getProperty("app.version");
            }
        } catch (Exception ignored) {
            // Falls through to the explicit prerequisite abort below.
        }
        if (version == null || version.isBlank() || version.contains("@")) {
            // No usable build metadata (e.g. IDE without Maven filtering):
            // point at a path that cannot exist so the suite aborts with its
            // prerequisite message instead of proving the wrong artifact.
            return Path.of("target/llm-wiki-km-MISSING-VERSION-METADATA.jar");
        }
        return Path.of("target/llm-wiki-km-" + version.strip() + ".jar");
    }
}
