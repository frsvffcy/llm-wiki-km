package org.km.llmwiki.release;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Release-candidate contract guard (Refs #430).
 *
 * <p>Locks the executable release boundary without building the JAR: the
 * reproducible timestamp authority, the clean-checkout + Maven-version
 * derivation + fail-closed procedure, the manifest/provenance hygiene, the
 * deterministic dependency inventory, the native pinned matrix, the
 * SUPPORTED/CANDIDATE/NOT-SUPPORTED notes scope, and the gated workflow that
 * can only build/verify/smoke/upload — never publish.
 */
@Tag("unit")
class ReleaseCandidateContractTest {

    private static final Path POM = Path.of("pom.xml");
    private static final Path WORKFLOW = Path.of(".github/workflows/release-candidate.yml");
    private static final Path PROCEDURE = Path.of("docs/release/release-candidate-procedure-v1.md");
    private static final Path NOTES = Path.of("docs/release/v0.1.0-release-notes.md");
    private static final Path MATRIX = Path.of("docs/release/native-capability-matrix.md");

    private static final List<String> SCRIPTS = List.of(
            "scripts/build-release-candidate.sh",
            "scripts/verify-reproducible-build.sh",
            "scripts/generate-dependency-inventory.sh",
            "scripts/check-release-bundle-hygiene.sh",
            "scripts/clean-install-smoke.sh",
            "scripts/candidate-backup-restore-smoke.sh",
            "scripts/check-release-readiness.sh");

    private static String read(Path path) throws Exception {
        assertThat(path).as("%s must exist", path).isRegularFile();
        return Files.readString(path);
    }

    @Test
    void reproducibleTimestampIsPinnedInPom() throws Exception {
        String pom = read(POM);
        assertThat(pom).contains("<project.build.outputTimestamp>2026-09-15T00:00:00Z</project.build.outputTimestamp>");
        assertThat(pom).contains("<version>0.1.0</version>");
        assertThat(pom).contains("<java.version>21</java.version>");
    }

    @Test
    void releaseScriptsExistAndStayExecutable() throws Exception {
        for (String script : SCRIPTS) {
            Path path = Path.of(script);
            assertThat(path).as("%s must exist", script).isRegularFile();
            assertThat(Files.isExecutable(path)).as("%s must be executable", script).isTrue();
            assertThat(Files.readString(path)).as("%s must fail closed", script).contains("set -eu");
        }
    }

    @Test
    void buildDerivesVersionFromMavenAndFailsClosedOnMismatch() throws Exception {
        String build = read(Path.of("scripts/build-release-candidate.sh"));
        // Challenge 2: no second version truth in the procedure.
        assertThat(build).contains("help:evaluate -Dexpression=project.version");
        assertThat(build).contains("help:evaluate -Dexpression=project.artifactId");
        assertThat(build).contains("--expected-version");
        assertThat(build).contains("!= expected");
        // Challenge 1: clean lifecycle, never renames a PR build output.
        assertThat(build).contains("mvn --batch-mode clean package");
        assertThat(build).contains("clean checkout");
        assertThat(build).contains("allow-dirty");
        // No live provider prerequisite.
        assertThat(build.toLowerCase(java.util.Locale.ROOT)).doesNotContain("openai_api_key");
        // Java 21 authority.
        assertThat(build).contains("requires Java 21");
        // Flyway identity derived from the chain, never hand-edited.
        assertThat(build).contains("db/migration");
        assertThat(build).contains("sort -V");
    }

    @Test
    void manifestCarriesProvenanceWithoutSecrets() throws Exception {
        String build = read(Path.of("scripts/build-release-candidate.sh"));
        for (String field : List.of(
                "sourceCommit", "artifactSha256", "javaVersion", "buildCommand",
                "procedureVersion", "flywayHighestMigration", "dependencyFingerprint",
                "acceptanceCorpusVersion", "createdAt", "runIdentity")) {
            assertThat(build).as("manifest must carry %s", field).contains(field);
        }
        assertThat(build).contains("release-candidate-procedure-v1");
        assertThat(build).contains("product-acceptance-corpus-v1");
        // createdAt never enters reproducible bytes; manifest hygiene is enforced.
        assertThat(build).contains("never enters JAR bytes");
    }

    @Test
    void dependencyInventoryIsDeterministicAndHygienic() throws Exception {
        String inventory = read(Path.of("scripts/generate-dependency-inventory.sh"));
        assertThat(inventory).contains("dependency:list");
        assertThat(inventory).contains("-Dsort=true");
        assertThat(inventory).contains("group");
        assertThat(inventory).contains("artifact");
        assertThat(inventory).contains("EXPECTED_HEADER=\"$(printf 'group\\tartifact\\ttype\\tversion\\tscope')\"");
        assertThat(inventory).doesNotContain("grep -q '^group\\tartifact\\ttype\\tversion\\tscope$'");
        // Weaker-option documentation, not a pasted console log.
        assertThat(inventory.toLowerCase(java.util.Locale.ROOT)).contains("weaker option");
        assertThat(inventory).contains("api[_-]?key");
        // The build must call the inventory; the manifest must fingerprint it.
        String build = read(Path.of("scripts/build-release-candidate.sh"));
        assertThat(build).contains("generate-dependency-inventory.sh");
        assertThat(build).contains("dependencyFingerprint");
    }

    @Test
    void bundleHygieneRefusesSecretsAndRuntimeState() throws Exception {
        String hygiene = read(Path.of("scripts/check-release-bundle-hygiene.sh"));
        assertThat(hygiene).contains("knowledge");
        assertThat(hygiene).contains("vault/");
        assertThat(hygiene).contains("OWNER_PASSWORD_VERIFIER");
        String build = read(Path.of("scripts/build-release-candidate.sh"));
        assertThat(build).contains("check-release-bundle-hygiene.sh");
    }

    @Test
    void cleanInstallUsesOnlyTheCandidateJar() throws Exception {
        String smoke = read(Path.of("scripts/clean-install-smoke.sh"));
        assertThat(smoke).contains("target/release-candidate/");
        assertThat(smoke).contains("java -jar");
        assertThat(smoke).contains("NOT_INITIALIZED");
        assertThat(smoke).contains("/api/v1/workspaces");
        assertThat(smoke).contains("/api/v1/system/deployment");
        assertThat(smoke).contains("no orphan");
        // Challenge 5: never boots the Maven reactor classpath.
        assertThat(smoke).doesNotContain("spring-boot:run");
        assertThat(smoke).doesNotContain("mvn test");
    }

    @Test
    void backupRestoreSmokeReusesTheAuthoritativeContractOnFreshRoot() throws Exception {
        String smoke = read(Path.of("scripts/candidate-backup-restore-smoke.sh"));
        assertThat(smoke).contains("deploy/backup/backup.sh");
        assertThat(smoke).contains("deploy/backup/restore.sh");
        assertThat(smoke).contains("TARGET_ROOT");
        assertThat(smoke).contains("fresh");
        assertThat(smoke).contains("flyway_schema_history");
        assertThat(smoke).contains("/api/v1/documents/");
        assertThat(smoke).contains("/api/v1/retrieval/inspect");
        // Secrets never enter the ordinary backup; corrupt/partial fail closed.
        assertThat(smoke).contains("secrets=excluded");
    }

    @Test
    void nativeMatrixIsPinnedAndNeverFloats() throws Exception {
        String matrix = read(MATRIX);
        assertThat(matrix).contains("v0.1.9");
        assertThat(matrix).contains("b959baa1d8dc88861b1edb337b8587178cdcb12d60b4998f9d10b6a82052d5d7");
        assertThat(matrix).contains("8282126333399ddfe98bbbcc7a1936e7252625aac49df056a98be602e46bfd29");
        // Pinned acquisition only: no floating latest-download URL.
        assertThat(matrix).doesNotContain("releases/latest");
        assertThat(matrix).contains("RETRIEVAL_VECTOR_UNAVAILABLE");
        assertThat(matrix).contains("ArcadeDB");
        // Derived Graph data must never become a canonical seed (prohibition, not promotion).
        assertThat(matrix).contains("canonical seed");
    }

    @Test
    void releaseNotesScopeNeverPromotesCandidates() throws Exception {
        String notes = read(NOTES);
        assertThat(notes).contains("SUPPORTED");
        assertThat(notes).contains("CANDIDATE");
        assertThat(notes).contains("NOT SUPPORTED");
        assertThat(notes).contains("LOCAL_ONLY");
        assertThat(notes).contains("PRIVATE_INGRESS");
        // Challenge 8: future candidates stay candidates (mentioned only to scope them out).
        assertThat(notes).contains("Public HTTPS");
        assertThat(notes).contains("OCR");
        assertThat(notes).contains("MCP");
        assertThat(notes).contains("REVERSE_PROXY_CANDIDATE");
        // Notes never redefine executable authority.
        assertThat(notes).contains("不得重新定義");
    }

    @Test
    void workflowIsGatedWithoutPublicationSideEffects() throws Exception {
        String workflow = read(WORKFLOW);
        // Challenge 9: ordinary execution only builds/verifies/smokes/uploads.
        assertThat(workflow).contains("workflow_dispatch");
        assertThat(workflow).doesNotContain("push:");
        assertThat(workflow).doesNotContain("pull_request");
        assertThat(workflow).contains("contents: read");
        assertThat(workflow).doesNotContain("contents: write");
        assertThat(workflow.toLowerCase(java.util.Locale.ROOT)).doesNotContain("gh release");
        assertThat(workflow).doesNotContain("softprops/action-gh-release");
        assertThat(workflow).doesNotContain("ncipollo/release-action");
        assertThat(workflow.toLowerCase(java.util.Locale.ROOT)).doesNotContain("create.*tag");
        assertThat(workflow).contains("retention-days: 14");
        // Pinned action majors follow the repository norm (@v4).
        assertThat(workflow).contains("actions/checkout@v4");
        assertThat(workflow).contains("actions/setup-java@v4");
        assertThat(workflow).contains("actions/upload-artifact@v4");
        // No secrets, no fork-with-secrets surface.
        assertThat(workflow.toLowerCase(java.util.Locale.ROOT)).doesNotContain("secrets.");
        // Reproducibility + smoke + #429 consumption + readiness in one chain.
        assertThat(workflow).contains("verify-reproducible-build.sh");
        assertThat(workflow).contains("clean-install-smoke.sh");
        assertThat(workflow).contains("candidate-backup-restore-smoke.sh");
        assertThat(workflow).contains("run-product-acceptance.sh");
        assertThat(workflow).contains("check-release-readiness.sh");
    }

    @Test
    void readinessRefusesReadyOnFailureOrSkip() throws Exception {
        String readiness = read(Path.of("scripts/check-release-readiness.sh"));
        assertThat(readiness).contains("READY_TO_PUBLISH");
        assertThat(readiness).contains("CONDITIONAL");
        assertThat(readiness).contains("NO-GO");
        // Challenge 10: #429 failure/skip blocks READY.
        assertThat(readiness).contains("release-evidence");
        assertThat(readiness).contains("v0.1.0-product-acceptance.json");
        // Publication stays human-authorized.
        assertThat(readiness).contains("human authorization");
    }

    @Test
    void procedureDocumentMatchesExecutableAuthority() throws Exception {
        String procedure = read(PROCEDURE);
        assertThat(procedure).contains("release-candidate-procedure-v1");
        assertThat(procedure).contains("project.build.outputTimestamp");
        assertThat(procedure).contains("help:evaluate");
        assertThat(procedure).contains("clean package");
    }
}
