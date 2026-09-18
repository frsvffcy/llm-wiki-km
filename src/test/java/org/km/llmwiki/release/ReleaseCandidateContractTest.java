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
    private static final Path NOTES_V010 = Path.of("docs/release/v0.1.0-release-notes.md");
    private static final Path NOTES_V011 = Path.of("docs/release/v0.1.1-release-notes.md");
    private static final Path NOTES = Path.of("docs/release/v0.2.0-release-notes.md");
    private static final Path BROWSER_CHECKLIST = Path.of("docs/release/v0.1.1-browser-smoke-checklist.md");
    private static final Path MATRIX = Path.of("docs/release/native-capability-matrix.md");

    private static final List<String> SCRIPTS = List.of(
            "scripts/build-release-candidate.sh",
            "scripts/verify-reproducible-build.sh",
            "scripts/generate-dependency-inventory.sh",
            "scripts/check-release-bundle-hygiene.sh",
            "scripts/clean-install-smoke.sh",
            "scripts/candidate-backup-restore-smoke.sh",
            "scripts/check-release-readiness.sh",
            "scripts/browser-first-mile-smoke.sh");

    private static String read(Path path) throws Exception {
        assertThat(path).as("%s must exist", path).isRegularFile();
        return Files.readString(path);
    }

    @Test
    void reproducibleTimestampIsPinnedInPom() throws Exception {
        String pom = read(POM);
        assertThat(pom).contains("<project.build.outputTimestamp>2026-09-15T00:00:00Z</project.build.outputTimestamp>");
        // #512 §C: the timestamp stays pinned across the 0.2.0 rebaseline; it is
        // only a reproducibility input and must never become dynamic build-time
        // data or a second version identity.
        // #512: project identity is now 0.2.0; v0.1.0 / v0.1.1 tags/artifacts stay immutable.
        assertThat(pom).contains("<version>0.2.0</version>");
        assertThat(pom).contains("<java.version>21</java.version>");
    }

    @Test
    void v010V011NotesStayImmutableAndV020NotesExist() throws Exception {
        // v0.1.0 / v0.1.1 notes are the immutable sources for their tags; the
        // 0.2.0 rebaseline must not rewrite them (no retag / asset overwrite).
        String v010 = read(NOTES_V010);
        assertThat(v010).contains("# v0.1.0 Release notes");
        String v011 = read(NOTES_V011);
        assertThat(v011).contains("# v0.1.1 Release notes");
        String v020 = read(NOTES);
        assertThat(v020).contains("# v0.2.0 Release notes");
        for (String token : List.of("SUPPORTED", "CANDIDATE", "NOT SUPPORTED")) {
            assertThat(v020).as("v0.2.0 notes must cover %s", token).contains(token);
        }
        // v0.2.0 scope tokens (Refs #512): rebaseline keeps the same support
        // boundary shape without promoting candidates.
        for (String token : List.of("#512", "LOCAL_ONLY", "PRIVATE_INGRESS",
                "Public HTTPS", "OCR", "MCP", "REVERSE_PROXY_CANDIDATE")) {
            assertThat(v020).as("v0.2.0 notes must cover %s", token).contains(token);
        }
        String checklist = read(BROWSER_CHECKLIST);
        assertThat(checklist).contains("Browser first-mile smoke checklist");
        assertThat(checklist).contains("Workspace → Inbox");
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
    void runtimeVersionMatchesMavenIdentity() throws Exception {
        // Refs #456 R1: no second version literal in the runtime path; the
        // generated version.properties (filtered from pom.xml) is the single
        // authority consumed through ApplicationVersion.
        String service = read(Path.of("src/main/java/org/km/llmwiki/system/SystemStatusService.java"));
        assertThat(service).doesNotContain("return \"0.1.0\"");
        assertThat(service).doesNotContain("return \"0.1.1\"");
        assertThat(service).doesNotContain("return \"0.2.0\"");
        assertThat(service).contains("ApplicationVersion");
        String provider = read(Path.of("src/main/java/org/km/llmwiki/system/ApplicationVersion.java"));
        assertThat(provider).contains("version.properties");
        assertThat(provider).contains("Implementation-Version");
        String template = read(Path.of("src/main/resources/version.properties"));
        assertThat(template).contains("app.version=@project.version@");
        String pom = read(Path.of("pom.xml"));
        assertThat(pom).contains("version.properties");
    }

    @Test
    void buildDerivesVersionFromMavenAndFailsClosedOnMismatch() throws Exception {
        String build = read(Path.of("scripts/build-release-candidate.sh"));
        // Challenge 2: no second version truth in the procedure.
        assertThat(build).contains("help:evaluate -Dexpression=project.version");
        assertThat(build).contains("help:evaluate -Dexpression=project.artifactId");
        assertThat(build).contains("--expected-version");
        assertThat(build).contains("!= expected");
        // Refs #456 R1: manifest project derives from Maven, never a literal.
        assertThat(build).contains("M_PROJECT=\"$ARTIFACT_ID\"");
        assertThat(build).doesNotContain("M_PROJECT=\"llm-wiki-km\"");        // Challenge 1: clean lifecycle, never renames a PR build output.
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
        // Refs #456 R1 (packaged proof): booted runtime version must equal
        // Maven and both JAR-internal identities.
        assertThat(smoke).contains("release_identity_jar_manifest_version");
        assertThat(smoke).contains("release_identity_jar_app_version");
        assertThat(smoke).contains("version identity PASS");
        // Refs #458: exact Maven-derived resolution, never an mtime pick.
        assertThat(smoke).doesNotContain("ls -t");
        assertThat(smoke).doesNotContain("head -1");
        assertThat(smoke).contains("help:evaluate -Dexpression=project.artifactId");
        assertThat(smoke).contains("help:evaluate -Dexpression=project.version");
        assertThat(smoke).contains("release_identity_expected_basename");
        assertThat(smoke).contains("release_identity_resolve_candidate_jar");
        assertThat(smoke).contains("release_identity_verify_jar_internal_version");
        assertThat(smoke).contains("release_identity_verify_candidate_sidecar_if_present");
    }

    @Test
    void backupRestoreSmokeReusesTheAuthoritativeContractOnFreshRoot() throws Exception {
        // NOTE: this script keeps one `head -1` for the just-created backup
        // artifact under a fresh mktemp dir (not candidate selection); the
        // candidate JAR itself must never resolve by mtime recency.
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
        // Refs #458: the candidate JAR itself never resolves by mtime
        // recency (the one remaining `head -1` below selects the
        // just-created backup artifact under a fresh mktemp dir, never the
        // release candidate).
        assertThat(smoke).doesNotContain("ls -t target/release-candidate");
        assertThat(smoke).doesNotContain("ls -t");
        assertThat(smoke).contains("release-identity.sh");
        assertThat(smoke).contains("help:evaluate -Dexpression=project.artifactId");
        assertThat(smoke).contains("help:evaluate -Dexpression=project.version");
        assertThat(smoke).contains("release_identity_expected_basename");
        assertThat(smoke).contains("release_identity_resolve_candidate_jar");
        assertThat(smoke).contains("release_identity_verify_jar_internal_version");
        assertThat(smoke).contains("release_identity_verify_candidate_sidecar_if_present");
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
        assertThat(workflow).contains("browser-first-mile-smoke.sh");
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
        // #454 §A → #512: version-agnostic report glob (no second version truth).
        assertThat(readiness).contains("*-product-acceptance.json");
        assertThat(readiness).doesNotContain("v0.1.0-product-acceptance.json");
        assertThat(readiness).doesNotContain("v0.1.1-product-acceptance.json");
        // Publication stays human-authorized.
        assertThat(readiness).contains("human authorization");
        // Refs #456 R4: single-manifest/bundle, Maven version match, artifact
        // SHA re-verification, and per-report sourceCommit cross-check.
        assertThat(readiness).contains("release_identity_require_single_file");
        assertThat(readiness).contains("release_identity_cross_check_source_commit");
        assertThat(readiness).contains("source identity mismatch");
        assertThat(readiness).contains("help:evaluate -Dexpression=project.version");
        assertThat(readiness).doesNotContain("head -1");
    }

    @Test
    void acceptanceReportFilenameStaysVersionNeutral() throws Exception {
        // Refs #512 §B: the acceptance report filename must not embed a release
        // version literal — the version truth lives in pom.xml + the manifest.
        String report = read(Path.of(
                "src/test/java/org/km/llmwiki/acceptance/ProductAcceptanceReport.java"));
        assertThat(report).contains("candidate-product-acceptance.json");
        assertThat(report).doesNotContain("v0.1.0-product-acceptance.json");
        assertThat(report).doesNotContain("v0.1.1-product-acceptance.json");
        assertThat(report).doesNotContain("v0.2.0-product-acceptance.json");
    }

    @Test
    void buildBundlesVersionedNotesWithoutSecondTruth() throws Exception {
        String build = read(Path.of("scripts/build-release-candidate.sh"));
        // Notes source is derived from Maven version, never a hardcoded v0.1.0.
        assertThat(build).contains("v${PROJECT_VERSION}-release-notes.md");
        assertThat(build).doesNotContain("v0.1.0-release-notes.md");
        assertThat(build).contains("versioned release notes missing");
    }

    @Test
    void acceptanceRunnerDerivesJarFromMavenTruth() throws Exception {
        String acceptance = read(Path.of("scripts/run-product-acceptance.sh"));
        // Refs #456 R2: exact Maven-derived filename, never an mtime pick.
        assertThat(acceptance).contains("help:evaluate -Dexpression=project.artifactId");
        assertThat(acceptance).contains("help:evaluate -Dexpression=project.version");
        assertThat(acceptance).contains("release_identity_expected_basename");
        assertThat(acceptance).contains("release_identity_verify_jar_internal_version");
        assertThat(acceptance).contains("release_identity_verify_sidecar");
        assertThat(acceptance).contains("--jar");
        assertThat(acceptance).doesNotContain("ls -t");
        assertThat(acceptance).doesNotContain("target/llm-wiki-km-0.1.0.jar");
    }

    @Test
    void browserFirstMileGateLocksPackagedFixes() throws Exception {
        String smoke = read(Path.of("scripts/browser-first-mile-smoke.sh"));
        assertThat(smoke).contains("[hidden]");
        assertThat(smoke).contains("data-parse-status");
        assertThat(smoke).contains("LIFECYCLE_FILTER_STATUSES");
        assertThat(smoke).contains("empty-state");
        assertThat(smoke).contains("target/release-candidate/");
        assertThat(smoke).contains("BOOT-INF/classes/static/");
        // Refs #456 R3 (via #458 resolver): exact resolution canonicalizes
        // before cd; never assemble via $OLDPWD.
        assertThat(smoke).contains("release_identity_resolve_candidate_jar");
        assertThat(smoke).contains("START_DIR");
        assertThat(smoke).doesNotContain("$OLDPWD/$JAR");
        // Refs #458: exact Maven-derived resolution, never an mtime pick.
        assertThat(smoke).doesNotContain("ls -t");
        assertThat(smoke).doesNotContain("head -1");
        assertThat(smoke).contains("help:evaluate -Dexpression=project.artifactId");
        assertThat(smoke).contains("help:evaluate -Dexpression=project.version");
        assertThat(smoke).contains("release_identity_expected_basename");
        assertThat(smoke).contains("release_identity_resolve_candidate_jar");
        assertThat(smoke).contains("release_identity_verify_jar_internal_version");
        assertThat(smoke).contains("release_identity_verify_candidate_sidecar_if_present");
    }

    @Test
    void candidateSmokeGatesShareTheExactResolverWithoutMtime() throws Exception {
        // Refs #458: Browser packaged smoke, clean-install smoke and
        // backup/restore smoke resolve the same Maven-derived exact filename
        // through the single library function — no gate keeps its own
        // `ls -t | head -1` selection.
        for (String script : List.of(
                "scripts/browser-first-mile-smoke.sh",
                "scripts/clean-install-smoke.sh",
                "scripts/candidate-backup-restore-smoke.sh")) {
            String smoke = read(Path.of(script));
            assertThat(smoke).as("%s must not select by mtime", script).doesNotContain("ls -t");
            assertThat(smoke).as("%s must reuse the exact resolver", script)
                    .contains("release_identity_resolve_candidate_jar");
            assertThat(smoke).as("%s must revalidate the sidecar contract", script)
                    .contains("release_identity_verify_candidate_sidecar_if_present");
        }
    }

    @Test
    void releaseIdentityHelpersAreSingleSourcedAndTested() throws Exception {
        // The fail-closed helpers live in exactly one library sourced by all
        // five consumers; behavior is proven by the hermetic shell suite.
        Path library = Path.of("scripts/release-identity.sh");
        assertThat(library).isRegularFile();
        String helpers = Files.readString(library);
        for (String fn : List.of(
                "release_identity_canonicalize",
                "release_identity_expected_basename",
                "release_identity_resolve_candidate_jar",
                "release_identity_verify_candidate_sidecar_if_present",
                "release_identity_verify_jar_internal_version",
                "release_identity_verify_sidecar",
                "release_identity_cross_check_source_commit",
                "release_identity_require_single_file")) {
            assertThat(helpers).as("library must define %s", fn).contains(fn + "()");
        }
        for (String consumer : List.of(
                "scripts/run-product-acceptance.sh",
                "scripts/browser-first-mile-smoke.sh",
                "scripts/check-release-readiness.sh",
                "scripts/clean-install-smoke.sh",
                "scripts/candidate-backup-restore-smoke.sh")) {
            assertThat(read(Path.of(consumer)))
                    .as("%s must source the single identity library", consumer)
                    .contains("release-identity.sh");
        }
        Path suite = Path.of("scripts/tests/test-release-identity.sh");
        assertThat(suite).isRegularFile();
        assertThat(Files.isExecutable(suite)).isTrue();
    }

    @Test
    void productAcceptanceHarnessOwnsDocumentAnalysisJourney() throws Exception {
        String harness = read(Path.of(
                "src/test/java/org/km/llmwiki/acceptance/ProductAcceptanceHarness.java"));
        assertThat(harness).contains("documentAnalysisJourney");
        assertThat(harness).contains("/api/v1/analysis/readiness");
        assertThat(harness).contains("/api/v1/analysis/jobs");
        assertThat(harness).contains("successCount");
        // No filesystem / DB shortcut: user actions only via public HTTP boundary.
        // Check executable patterns (with paren / SQL verb) so prose comments
        // describing the prohibition do not trip the guard.
        assertThat(harness).doesNotContain("Files.write(");
        assertThat(harness).doesNotContain("Files.createFile(");
        assertThat(harness).doesNotContain("INSERT INTO setting");
        assertThat(harness).doesNotContain("INSERT INTO document_analysis");
        assertThat(harness).doesNotContain("INSERT INTO processing_job");
    }

    @Test
    void productAcceptanceExecutesBashSmokeThroughItsShebang() throws Exception {
        String acceptance = read(Path.of("scripts/run-product-acceptance.sh"));
        assertThat(acceptance).contains("scripts/sqlite-vec-jdbc-smoke.sh \"$VECTOR_LIB\"");
        assertThat(acceptance).contains("scripts/sqlite-vec-jdbc-smoke.sh \"$VECTOR_EXTENSION_PATH\"");
        assertThat(acceptance).doesNotContain("sh scripts/sqlite-vec-jdbc-smoke.sh");
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
