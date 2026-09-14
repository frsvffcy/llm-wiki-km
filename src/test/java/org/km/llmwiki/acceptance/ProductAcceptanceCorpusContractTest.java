package org.km.llmwiki.acceptance;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract for the versioned acceptance corpus and harness boundary
 * (Refs #429 §A–§B).
 *
 * <p>Fast tier: no Spring context, no network, no filesystem mutation beyond
 * temp reads.
 */
@Tag("contract")
class ProductAcceptanceCorpusContractTest {

    @Test
    void corpusVersionIsExplicitAndStable() {
        assertThat(ProductAcceptanceCorpusV1.VERSION)
                .isEqualTo("product-acceptance-corpus-v1");
        assertThat(ProductAcceptanceCorpusV1.PROCEDURE_VERSION)
                .isEqualTo("product-acceptance-procedure-v1");
    }

    @Test
    void corpusCoversRequiredShapes() {
        List<ProductAcceptanceCorpusV1.Fixture> fixtures = ProductAcceptanceCorpusV1.fixtures();
        assertThat(fixtures).hasSize(3);
        assertThat(fixtures.stream().map(ProductAcceptanceCorpusV1.Fixture::fileName).toList())
                .contains("acceptance-guide.md", "acceptance-runbook.md",
                        "acceptance-structured.html");
        // UTF-8 Markdown/text + one Tika-structured fixture + CJK + exact-token anchors.
        assertThat(ProductAcceptanceCorpusV1.guideMarkdown())
                .contains("金鑰工作區", ProductAcceptanceCorpusV1.ANCHOR_TOKEN,
                        ProductAcceptanceCorpusV1.PROPERTY_LINE);
        assertThat(ProductAcceptanceCorpusV1.runbookMarkdown())
                .contains("金鑰工作區", ProductAcceptanceCorpusV1.ANCHOR_TOKEN);
        assertThat(ProductAcceptanceCorpusV1.structuredHtml())
                .contains("<table>", ProductAcceptanceCorpusV1.PROPERTY_ANCHOR,
                        ProductAcceptanceCorpusV1.ANCHOR_TOKEN);
        // At least two cross-retrievable sources share the anchor.
        long sharing = fixtures.stream()
                .filter(fixture -> fixture.content()
                        .contains(ProductAcceptanceCorpusV1.ANCHOR_TOKEN))
                .count();
        assertThat(sharing).isGreaterThanOrEqualTo(2);
    }

    @Test
    void corpusContainsNoPrivateMaterial() {
        String combined = ProductAcceptanceCorpusV1.guideMarkdown()
                + ProductAcceptanceCorpusV1.runbookMarkdown()
                + ProductAcceptanceCorpusV1.structuredHtml();
        assertThat(combined)
                .doesNotContain("sk-", "Bearer ", "BEGIN PRIVATE KEY", "/Users/", "/home/",
                        "api-key", "password");
        for (var fixture : ProductAcceptanceCorpusV1.fixtures()) {
            assertThat(fixture.content().getBytes(StandardCharsets.UTF_8))
                    .as("fixture %s must be bounded", fixture.fileName())
                    .hasSizeLessThan(100_000);
        }
    }

    @Test
    void deterministicEmbeddingVectorsAreStableWithoutRandomness() {
        float[] first = DeterministicAcceptanceProviderStub.vectorFor("金鑰工作區錨點");
        float[] second = DeterministicAcceptanceProviderStub.vectorFor("金鑰工作區錨點");
        float[] other = DeterministicAcceptanceProviderStub.vectorFor("完全不同的輸入內容");
        assertThat(first).hasSize(8).containsExactly(second);
        assertThat(first).isNotEqualTo(other);
        for (float value : first) {
            assertThat(value).isBetween(-1.0f, 1.0f);
        }
    }

    @Test
    void reportRedactsCredentialsAndStaysBounded() {
        ProductAcceptanceReport report = new ProductAcceptanceReport();
        report.add("demo", ProductAcceptanceReport.Verdict.PASS,
                "Authorization: Bearer sk-secret-value api-key=sk-other");
        assertThat(report.steps().get(0).detail()).doesNotContain("sk-secret-value", "sk-other");
        // SKIP blocks FULL-GO: no fake-green when prerequisites are absent.
        assertThat(report.overall()).isEqualTo("FULL-GO");
        report.add("vector-prerequisite", ProductAcceptanceReport.Verdict.SKIP,
                "native unavailable");
        assertThat(report.overall()).isEqualTo("CONDITIONAL");
        report.add("broken", ProductAcceptanceReport.Verdict.FAIL, "boom");
        assertThat(report.overall()).isEqualTo("NO-GO");
    }

    @Test
    void harnessUserActionsNeverTouchPersistenceOrVaultDirectly() throws Exception {
        // Static boundary: the HTTP client + harness must drive user actions only
        // through the public /api/v1 surface — no direct DB inserts, no vault writes.
        Path base = Path.of(System.getProperty("user.dir"),
                "src/test/java/org/km/llmwiki/acceptance");
        List<String> forbidden = List.of(
                "INSERT INTO knowledge_", "INSERT INTO document", "INSERT INTO source_chunk",
                "INSERT INTO workspace", "DELETE FROM knowledge_", "JdbcClient", "DSLContext",
                "Files.writeString", "Files.write(");
        for (String file : List.of("ProductAcceptanceHttpClient.java",
                "ProductAcceptanceHarness.java")) {
            String source = Files.readString(base.resolve(file), StandardCharsets.UTF_8);
            for (String marker : forbidden) {
                assertThat(source).as(file + " must not contain " + marker)
                        .doesNotContain(marker);
            }
            assertThat(source).as(file + " must only call the public API surface")
                    .contains("/api/v1/");
        }
    }
}
