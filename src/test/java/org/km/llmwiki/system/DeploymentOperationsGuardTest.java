package org.km.llmwiki.system;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Operations packaging and exposure guard (#418).
 *
 * <p>Locks the invariants the Remote Personal Deployment promotion depends
 * on: the raw backend never binds a wildcard address, forwarder and proxy
 * examples always target the loopback backend, packaging stays
 * single-instance on Java 21 without a second writer, and no parallel
 * backup package, backup endpoint, or out-of-scope platform (PostgreSQL,
 * Kubernetes, HA) is smuggled in through this adoption.
 */
@Tag("unit")
class DeploymentOperationsGuardTest {

    private static final Path PRODUCTION_ROOT = Path.of("src/main/java/org/km/llmwiki");
    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");
    private static final Path DEPLOY_ROOT = Path.of("deploy");

    @Test
    void deployArtifactsExistAndStayLoopbackOnly() throws Exception {
        assertThat(DEPLOY_ROOT).isDirectory();
        List<Path> files;
        try (Stream<Path> paths = Files.walk(DEPLOY_ROOT)) {
            files = paths.filter(Files::isRegularFile).toList();
        }
        assertThat(files).isNotEmpty();

        List<String> wildcard = new ArrayList<>();
        for (Path file : files) {
            String text = Files.readString(file);
            if (text.contains("0.0.0.0")) {
                wildcard.add(file.toString());
            }
        }
        assertThat(wildcard)
                .as("deploy/ must never bind the backend or a forwarder to all interfaces")
                .isEmpty();
    }

    @Test
    void forwarderExamplesTargetLoopbackBackend() throws Exception {
        Path forwarder = DEPLOY_ROOT.resolve("forwarder");
        assertThat(forwarder).isDirectory();
        List<Path> files;
        try (Stream<Path> paths = Files.walk(forwarder)) {
            files = paths.filter(Files::isRegularFile).toList();
        }
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            assertThat(Files.readString(file))
                    .as("%s must forward to the loopback backend", file)
                    .contains("127.0.0.1:8765");
        }
    }

    @Test
    void containerPackagingStaysSingleInstanceOnJava21() throws Exception {
        String dockerfile = Files.readString(
                DEPLOY_ROOT.resolve("container/Dockerfile"));
        assertThat(dockerfile).contains("21").contains("USER");
        assertThat(dockerfile).doesNotContain("0.0.0.0");

        String compose = Files.readString(
                DEPLOY_ROOT.resolve("container/compose.yml"));
        assertThat(compose).doesNotContain("replicas:");
        assertThat(compose).doesNotContain("0.0.0.0");
    }

    @Test
    void ownerEnvExampleCoversTheFullBrowserIngressContract() throws Exception {
        // #422 §F: copy-and-configure must not leave an operator guessing the
        // Host/Origin/cookie lines — a partial copy can never report SUPPORTED.
        String example = Files.readString(
                DEPLOY_ROOT.resolve("systemd/owner.env.example"));

        for (String required : List.of(
                "DEPLOYMENT_BROWSER_ORIGIN=",
                "OWNER_ALLOWED_HOSTS=",
                "OWNER_ALLOWED_ORIGINS=",
                "OWNER_COOKIE_SECURE=",
                "OWNER_AUTH_ENABLED=",
                "OWNER_PASSWORD_VERIFIER=",
                "DEPLOYMENT_MODE=PRIVATE_INGRESS",
                "DEPLOYMENT_FORWARDER_BINDS=",
                "DEPLOYMENT_FORWARDER_TARGET=")) {
            assertThat(example)
                    .as("owner.env.example must document %s", required)
                    .contains(required);
        }
        // #423: the example must teach the versioned verifier, never the legacy
        // hash as a current credential line.
        assertThat(example.lines().anyMatch(line ->
                        line.strip().startsWith("OWNER_PASSWORD_HASH=")))
                .as("owner.env.example must not offer a legacy hash credential line")
                .isFalse();
        assertThat(example).doesNotContain("0.0.0.0");
    }

    @Test
    void noBackupPackageOrEndpointIsIntroduced() throws Exception {
        assertThat(PRODUCTION_ROOT.resolve("backup")).doesNotExist();

        List<Path> sources;
        try (Stream<Path> paths = Files.walk(PRODUCTION_ROOT)) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            String text = Files.readString(source);
            if (text.contains("/api/v1/backups") || text.contains("/api/v1/restore")) {
                offenders.add(source.toString());
            }
        }
        assertThat(offenders)
                .as("backup/restore stays an operator procedure; no new REST capability")
                .isEmpty();
    }

    @Test
    void noOutOfScopePlatformIsIntroduced() throws Exception {
        List<Path> sources;
        try (Stream<Path> paths = Files.walk(PRODUCTION_ROOT)) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            String text = source.toString().endsWith("DeploymentOperationsGuardTest.java")
                    ? ""
                    : Files.readString(source);
            for (String token : List.of("postgres", "kubernetes", "replicas:", "leader election")) {
                if (text.toLowerCase(java.util.Locale.ROOT).contains(token)) {
                    offenders.add(source + " contains " + token);
                }
            }
        }
        assertThat(offenders)
                .as("remote adoption must not smuggle PostgreSQL/Kubernetes/HA scope")
                .isEmpty();

        String yml = Files.readString(APPLICATION_YML);
        assertThat(yml.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("postgres")
                .doesNotContain("kubernetes");
    }
}
