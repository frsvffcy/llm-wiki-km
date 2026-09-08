package org.km.llmwiki.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider-neutrality and identity contract for graph evidence admission: admission types stay
 * vendor-free, evidence identity stays canonical (never a graph stable id), and rejections stay
 * bounded diagnostics.
 */
@Tag("contract")
class GraphEvidenceVendorNeutralContractTest {

    private static final List<String> FORBIDDEN_REFERENCES = List.of(
            "arcadedb", "neo4j", "ryugraph", "bigquery", "spanner", "cypher", "gql", "sql-pgq",
            "vendor node", "vendor edge", "vendor record", "vendor score");

    private static final Set<String> ADMISSION_SOURCES = Set.of(
            "GraphEvidenceAdmissionService.java", "GraphEvidenceAdmissionRequest.java",
            "GraphEvidenceAdmissionResult.java", "GraphEvidenceAdmissionBudget.java",
            "GraphEvidenceRejectionReason.java", "GraphCandidateRejection.java");

    @Test
    void graphEvidenceAdmissionSourcesContainNoVendorApiOrQueryReference() throws IOException {
        Path sourceRoot = Path.of("src/main/java/org/km/llmwiki/rag");
        assertThat(Files.isDirectory(sourceRoot)).isTrue();

        try (var paths = Files.walk(sourceRoot)) {
            List<Path> admissionSources = paths
                    .filter(path -> ADMISSION_SOURCES.contains(
                            path.getFileName().toString()))
                    .toList();
            assertThat(admissionSources).hasSameSizeAs(ADMISSION_SOURCES);
            for (Path source : admissionSources) {
                String normalized = readLowerCase(source);
                for (String forbiddenReference : FORBIDDEN_REFERENCES) {
                    var assertion = assertThat(normalized)
                            .as("graph evidence admission must remain vendor-neutral: %s in %s",
                                    forbiddenReference, source.getFileName());
                    if ("vendor score".equals(forbiddenReference)) {
                        assertion.doesNotMatch("(?s).*vendor\\s+score.*");
                    } else {
                        assertion.doesNotContain(forbiddenReference);
                    }
                }
            }
        }
    }

    @Test
    void admissionContractSurfaceUsesOnlyProviderNeutralApplicationTypes() throws Exception {
        Method admit = GraphEvidenceAdmissionService.class.getMethod("admit",
                GraphEvidenceAdmissionRequest.class);
        assertThat(admit.getReturnType()).isEqualTo(GraphEvidenceAdmissionResult.class);

        assertThat(GraphEvidenceAdmissionRequest.class.getRecordComponents())
                .extracting(component -> component.getType().getPackageName())
                .allMatch(packageName -> packageName.equals("org.km.llmwiki.rag")
                        || packageName.equals("org.km.llmwiki.graph")
                        || packageName.equals("java.util"));
        assertThat(GraphEvidenceAdmissionResult.class.getRecordComponents())
                .extracting(component -> component.getType().getPackageName())
                .allMatch(packageName -> packageName.equals("org.km.llmwiki.rag")
                        || packageName.equals("org.km.llmwiki.graph")
                        || packageName.equals("java.util")
                        || packageName.equals("java.lang"));

        assertThat(EvidenceItem.class.getRecordComponents())
                .extracting(component -> component.getType().getPackageName())
                .allMatch(packageName -> packageName.equals("org.km.llmwiki.rag")
                        || packageName.equals("java.lang"));
    }

    @Test
    void graphDerivedEvidenceReusesTheCanonicalRetrievalIdentityContract() throws Exception {
        EvidenceWorkspace workspace = new EvidenceWorkspace(1L, "test");
        EvidenceItem wiki = new EvidenceItem(EvidenceKind.WIKI, "knowledge-id", workspace, 1.0d,
                "content", null, false, "hash", "knowledge-id", null, null, null, null,
                null, null, null, null, null, null, null);
        EvidenceItem source = new EvidenceItem(EvidenceKind.SOURCE_CHUNK, "77", workspace, 1.0d,
                "content", null, false, "hash", null, null, null, null, null,
                77L, null, null, null, null, null, null);
        // The identity string is exactly the dedup key RetrievalService and future fusion use.
        assertThat(wiki.stableIdentity()).isEqualTo("WIKI:knowledge-id");
        assertThat(source.stableIdentity()).isEqualTo("SOURCE_CHUNK:77");
        assertThat(EvidenceItem.class.getMethod("stableIdentity").getDeclaringClass())
                .isEqualTo(EvidenceItem.class);
    }

    @Test
    void rejectionDiagnosticsStayBoundedAndTyped() {
        var rejection = new GraphCandidateRejection(
                org.km.llmwiki.graph.GraphEntityIdentity.of(
                        new org.km.llmwiki.graph.GraphWorkspaceScope(1),
                        org.km.llmwiki.graph.GraphEntityType.WIKI_PAGE, "wiki-page:page"),
                GraphEvidenceRejectionReason.AUTHORITY_STALE, "x".repeat(10_000));
        assertThat(rejection.detail().codePointCount(0, rejection.detail().length()))
                .isEqualTo(GraphCandidateRejection.MAX_DETAIL_CODE_POINTS);
    }

    private String readLowerCase(Path path) {
        try {
            String source = Files.readString(path)
                    .replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("(?m)//.*$", " ")
                    .replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"")
                    .replaceAll("'(?:\\\\.|[^'\\\\])*'", "''");
            return source.toLowerCase(Locale.ROOT);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read graph evidence admission source",
                    exception);
        }
    }
}
