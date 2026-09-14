package org.km.llmwiki.docs;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture Version-of-Truth anti-drift guard (#424 §C).
 *
 * <p>Locks the known drift classes that reappeared right after #410: stale
 * fixed migration ranges presented as latest truth, finished Remote Deployment
 * work still described as unfinished, missing security/deployment navigation,
 * regressed normalization semantics, and wrongly promoted public bindings.
 * The guard only reads current-doc wording and legacy frozen markers — it
 * never asserts runtime behavior and never promotes the VoT into a
 * schema/API authority. Any actual mismatch is still decided by Flyway,
 * Controllers, and tests.
 */
@Tag("unit")
class ArchitectureVoTAntiDriftTest {

    private static final Path ARCHITECTURE = Path.of("docs/architecture");
    private static final Path LEGACY = ARCHITECTURE.resolve("legacy");
    private static final Path GUIDE = Path.of("docs/guides/architecture-learning-guide.md");

    private static String read(Path file) throws Exception {
        assertThat(file).as("current doc %s must exist", file).exists();
        return Files.readString(file);
    }

    @Test
    void currentDocsClaimNoFixedMigrationRangeAsLatestTruth() throws Exception {
        // The executable truth is the Flyway directory; a fixed range string
        // would silently drift again on the next migration.
        assertThat(read(ARCHITECTURE.resolve("system-overview.md")))
                .as("system-overview must not present a fixed V-range as latest truth")
                .doesNotContain("V1～V33");
        assertThat(read(ARCHITECTURE.resolve("schema.md")))
                .as("schema must not present a fixed V-range as latest truth")
                .doesNotContain("即 V1～V33");
        assertThat(read(GUIDE))
                .as("learning guide must not present a fixed V-range as latest truth")
                .doesNotContain("V1～V33 chain");
    }

    @Test
    void remoteDeploymentIsNotDescribedAsUnfinishedWork() throws Exception {
        assertThat(read(ARCHITECTURE.resolve("use-cases.md")))
                .as("use-cases must not leave Remote Deployment with the finished #393 owner")
                .doesNotContain("由 #393 持有");
    }

    @Test
    void securityAndDeploymentHoldersStayNavigable() throws Exception {
        assertThat(read(ARCHITECTURE.resolve("api.md")))
                .as("api navigation must reach the owner session holder")
                .contains("owner/session");
        assertThat(read(ARCHITECTURE.resolve("api.md")))
                .as("api navigation must reach the deployment readiness holder")
                .contains("/api/v1/system/deployment");
        assertThat(read(ARCHITECTURE.resolve("capability-map.md")))
                .as("capability map must own the security boundary")
                .contains("web/security");
        assertThat(read(ARCHITECTURE.resolve("capability-map.md")))
                .as("capability map must own the deployment readiness")
                .contains("DeploymentReadinessController");
    }

    @Test
    void normalizationCurrentSemanticsDoNotRegress() throws Exception {
        assertThat(read(ARCHITECTURE.resolve("schema.md")))
                .as("schema must keep the selected-Cf production default")
                .contains("normalization-policy-v2-selected-cf-strip");
        assertThat(read(GUIDE))
                .as("learning guide must keep the normalization rollback target")
                .contains("normalization-policy-v1-current");
    }

    @Test
    void publicBindingsAreNeverPromotedToSupported() throws Exception {
        String useCases = read(ARCHITECTURE.resolve("use-cases.md"));
        assertThat(useCases)
                .as("use-cases must keep public HTTPS a candidate")
                .contains("CANDIDATE");
        assertThat(useCases)
                .as("use-cases must keep direct raw bind rejected")
                .contains("REJECT");
    }

    @Test
    void issueLineageStaysLinked() throws Exception {
        assertThat(read(ARCHITECTURE.resolve("use-cases.md")))
                .as("use-cases must link the deployment adoption lineage")
                .contains("#417");
        assertThat(read(ARCHITECTURE.resolve("use-cases.md")))
                .as("use-cases must link the ingress correctness gate")
                .contains("#422");
        assertThat(read(ARCHITECTURE.resolve("system-overview.md")))
                .as("system-overview must link the credential hardening")
                .contains("#423");
    }

    @Test
    void legacySnapshotsStayFrozenHistorical() throws Exception {
        List<Path> frozen;
        try (Stream<Path> paths = Files.walk(LEGACY)) {
            frozen = paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md"))
                    .toList();
        }
        assertThat(frozen).as("legacy snapshots must exist").isNotEmpty();
        for (Path snapshot : frozen) {
            String text = Files.readString(snapshot);
            assertThat(text)
                    .as("%s must stay a frozen HISTORICAL snapshot", snapshot)
                    .contains("HISTORICAL");
        }
    }
}
