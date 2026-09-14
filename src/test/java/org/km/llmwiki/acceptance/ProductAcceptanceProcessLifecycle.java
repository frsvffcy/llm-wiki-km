package org.km.llmwiki.acceptance;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lifecycle for a clean built-application JAR subprocess (Refs #429 §B).
 *
 * <p>Starts {@code java -jar <jar> --server.port=<free>} with a dynamic,
 * isolated temp environment (never the developer {@code data/} tree), waits
 * for loopback readiness via bounded status polling (no sleep-luck), and
 * guarantees process/port/temp cleanup on failure.
 */
public final class ProductAcceptanceProcessLifecycle implements AutoCloseable {

    private final Path jar;
    private final Map<String, String> environment;
    private Process process;
    private int port;
    private Path logFile;

    public ProductAcceptanceProcessLifecycle(Path jar, Map<String, String> environment) {
        this.jar = jar;
        this.environment = Map.copyOf(environment);
    }

    public int start(Path logDirectory) throws IOException {
        port = freePort();
        logFile = logDirectory.resolve("acceptance-jar-" + port + "-stdout.log");
        List<String> command = new ArrayList<>(List.of(
                javaExecutable(), "-jar", jar.toAbsolutePath().toString(),
                "--server.port=" + port));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(environment);
        // LOCAL_ONLY contract: the loopback forwarder target must address the actual
        // backend port. Aligned here so the deployment fail-fast validator sees the
        // real bind; an explicitly caller-configured target is never overridden, so a
        // genuine mismatch still fails closed at startup.
        builder.environment().putIfAbsent("DEPLOYMENT_FORWARDER_TARGET", "127.0.0.1:" + port);
        builder.redirectOutput(logFile.toFile());
        builder.redirectErrorStream(true);
        process = builder.start();
        waitForReadiness();
        return port;
    }

    public int port() {
        return port;
    }

    public Path logFile() {
        return logFile;
    }

    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void waitForReadiness() {
        var client = new ProductAcceptanceHttpClient("http://127.0.0.1:" + port);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (Instant.now().isBefore(deadline)) {
            if (process != null && !process.isAlive()) {
                throw new IllegalStateException(
                        "acceptance JAR exited during startup; see " + logFile);
            }
            try {
                var response = client.get("/api/v1/system/status");
                if (response.status() == 200
                        && !response.json(client.mapper()).path("data").path("status")
                        .asText("").isBlank()) {
                    return;
                }
            } catch (Exception ignored) {
                // Not ready yet; keep polling until the deadline.
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for readiness",
                        interrupted);
            }
        }
        throw new IllegalStateException(
                "acceptance JAR not ready within deadline; see " + logFile);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        if (home != null && !home.isBlank()) {
            return Path.of(home, "bin", "java").toString();
        }
        return "java";
    }
}
