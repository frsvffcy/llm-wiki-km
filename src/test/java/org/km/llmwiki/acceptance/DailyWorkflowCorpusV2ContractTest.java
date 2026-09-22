package org.km.llmwiki.acceptance;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract for the versioned daily-workflow corpus v2, typed finding schema
 * and harness boundary (Refs #467 §A–§C).
 *
 * <p>Fast tier: no Spring context, no network, no filesystem mutation beyond
 * temp reads.
 */
@Tag("contract")
class DailyWorkflowCorpusV2ContractTest {

    @Test
    void corpusVersionIsExplicitAndStable() {
        assertThat(DailyWorkflowCorpusV2.VERSION)
                .isEqualTo("daily-workflow-corpus-v2");
        assertThat(DailyWorkflowCorpusV2.PROCEDURE_VERSION)
                .isEqualTo("daily-workflow-procedure-v2");
    }

    @Test
    void corpusCoversRequiredShapes() {
        List<DailyWorkflowCorpusV2.Fixture> fixtures = DailyWorkflowCorpusV2.fixtures();
        assertThat(fixtures).hasSize(5);
        assertThat(fixtures.stream().map(DailyWorkflowCorpusV2.Fixture::fileName).toList())
                .contains("daily-guide.md", "daily-runbook.md",
                        "daily-config.properties", "daily-structured.html",
                        "daily-revision.md");
        // CJK + exact-token anchors on the guide.
        assertThat(DailyWorkflowCorpusV2.guideMarkdown())
                .contains("日常工作區", DailyWorkflowCorpusV2.DAILY_ANCHOR,
                        DailyWorkflowCorpusV2.PROPERTY_LINE,
                        DailyWorkflowCorpusV2.CODE_ANCHOR);
        // Cross-retrievable second markdown shares the daily anchor.
        assertThat(DailyWorkflowCorpusV2.runbookMarkdown())
                .contains("日常工作區", DailyWorkflowCorpusV2.DAILY_ANCHOR);
        // Code-like / property-like plain-text fixture.
        assertThat(DailyWorkflowCorpusV2.configProperties())
                .contains(DailyWorkflowCorpusV2.PROPERTY_LINE,
                        DailyWorkflowCorpusV2.PROPERTY_ANCHOR,
                        DailyWorkflowCorpusV2.CODE_ANCHOR);
        // Tika-structured fixture with headings + table.
        assertThat(DailyWorkflowCorpusV2.structuredHtml())
                .contains("<table>", DailyWorkflowCorpusV2.PROPERTY_ANCHOR,
                        DailyWorkflowCorpusV2.DAILY_ANCHOR);
        // Heading/table structure observable in markdown.
        assertThat(DailyWorkflowCorpusV2.guideMarkdown())
                .contains("## ", "| 欄位 |");
        // Revision pair: v1 carries V1-only anchor, v2 drops it and adds V2.
        assertThat(DailyWorkflowCorpusV2.revisionV1())
                .contains(DailyWorkflowCorpusV2.REVISION_ANCHOR_V1,
                        DailyWorkflowCorpusV2.DAILY_ANCHOR)
                .doesNotContain(DailyWorkflowCorpusV2.REVISION_ANCHOR_V2);
        assertThat(DailyWorkflowCorpusV2.revisionV2())
                .contains(DailyWorkflowCorpusV2.REVISION_ANCHOR_V2,
                        DailyWorkflowCorpusV2.DAILY_ANCHOR)
                .doesNotContain(DailyWorkflowCorpusV2.REVISION_ANCHOR_V1);
        // At least two cross-retrievable sources share the daily anchor.
        long sharing = fixtures.stream()
                .filter(fixture -> fixture.content()
                        .contains(DailyWorkflowCorpusV2.DAILY_ANCHOR))
                .count();
        assertThat(sharing).isGreaterThanOrEqualTo(2);
    }

    @Test
    void noEvidenceProbeNeverMatchesAnyFixture() {
        String combined = DailyWorkflowCorpusV2.guideMarkdown()
                + DailyWorkflowCorpusV2.runbookMarkdown()
                + DailyWorkflowCorpusV2.configProperties()
                + DailyWorkflowCorpusV2.structuredHtml()
                + DailyWorkflowCorpusV2.revisionV1()
                + DailyWorkflowCorpusV2.revisionV2();
        assertThat(combined).doesNotContain(DailyWorkflowCorpusV2.NO_EVIDENCE_TOKEN);
        for (var fixture : DailyWorkflowCorpusV2.fixtures()) {
            assertThat(fixture.content()).doesNotContain(DailyWorkflowCorpusV2.NO_EVIDENCE_TOKEN);
        }
    }

    @Test
    void corpusContainsNoPrivateMaterialAndStaysBounded() {
        String combined = DailyWorkflowCorpusV2.guideMarkdown()
                + DailyWorkflowCorpusV2.runbookMarkdown()
                + DailyWorkflowCorpusV2.configProperties()
                + DailyWorkflowCorpusV2.structuredHtml()
                + DailyWorkflowCorpusV2.revisionV1()
                + DailyWorkflowCorpusV2.revisionV2()
                + DailyWorkflowCorpusV2.NO_ANSWER_QUESTION;
        assertThat(combined)
                .doesNotContain("sk-", "Bearer ", "BEGIN PRIVATE KEY", "/Users/", "/home/",
                        "/tmp/", "api-key", "password");
        for (var fixture : DailyWorkflowCorpusV2.fixtures()) {
            assertThat(fixture.content().getBytes(StandardCharsets.UTF_8))
                    .as("fixture %s must be bounded", fixture.fileName())
                    .hasSizeLessThan(100_000);
        }
    }

    @Test
    void findingSchemaCoversRequiredCategoriesAndBlockerSemantics() {
        assertThat(DailyWorkflowFindingReport.Category.values())
                .contains(DailyWorkflowFindingReport.Category.PRODUCT_BUG,
                        DailyWorkflowFindingReport.Category.UX_FRICTION,
                        DailyWorkflowFindingReport.Category.OPERABILITY,
                        DailyWorkflowFindingReport.Category.RETRIEVAL_QUALITY,
                        DailyWorkflowFindingReport.Category.KNOWLEDGE_MAINTENANCE,
                        DailyWorkflowFindingReport.Category.CANDIDATE_TRIGGER,
                        DailyWorkflowFindingReport.Category.NO_EVIDENCE);
        DailyWorkflowFindingReport report = new DailyWorkflowFindingReport();
        report.add("journey", "daily-guide.md",
                DailyWorkflowFindingReport.Category.RETRIEVAL_QUALITY, false, true,
                "expected", "observed", "surface", "authority", "metrics", "trigger");
        assertThat(report.hasBlocker()).isFalse();
        assertThat(report.overall()).isEqualTo("FULL-GO");
        report.add("revision", "daily-revision.md",
                DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                "expected", "observed", "surface", "authority", "metrics", "trigger");
        assertThat(report.hasBlocker()).isTrue();
        assertThat(report.overall()).isEqualTo("NO-GO");
    }

    @Test
    void findingReportRedactsCredentialsPathsAndStaysBounded() {
        DailyWorkflowFindingReport report = new DailyWorkflowFindingReport();
        report.add("demo", "daily-guide.md",
                DailyWorkflowFindingReport.Category.OPERABILITY, false, true,
                "Authorization: Bearer sk-secret-value api-key=sk-other /Users/example/vault/a.md",
                "observed /home/example/archive/b.md /tmp/c.md",
                "surface", "authority", "metrics", "trigger");
        var finding = report.findings().get(0);
        assertThat(finding.expected())
                .doesNotContain("sk-secret-value", "sk-other", "/Users/example");
        assertThat(finding.observed())
                .doesNotContain("/home/example", "/tmp/c.md");
        assertThat(finding.expected().length()).isLessThanOrEqualTo(500);
    }

    @Test
    void harnessUserActionsNeverTouchCanonicalPersistenceOrVault() throws Exception {
        // Static boundary: the V2 HTTP client + harness must drive user actions
        // only through the public /api/v1 surface — no direct DB inserts, no
        // vault/archive writes. The single inbox-scoped user-edit simulation
        // (overwrite + rescan) is the documented replacement path, not a
        // canonical shortcut.
        Path base = Path.of(System.getProperty("user.dir"),
                "src/test/java/org/km/llmwiki/acceptance");
        List<String> forbidden = List.of(
                "INSERT INTO knowledge_", "INSERT INTO document", "INSERT INTO source_chunk",
                "INSERT INTO workspace", "DELETE FROM knowledge_", "JdbcClient", "DSLContext",
                "resolve(\"vault\")", "resolve(\"archive\")");
        for (String file : List.of("ProductAcceptanceHttpClient.java",
                "DailyWorkflowHarnessV2.java")) {
            String source = Files.readString(base.resolve(file), StandardCharsets.UTF_8);
            for (String marker : forbidden) {
                assertThat(source).as(file + " must not contain " + marker)
                        .doesNotContain(marker);
            }
            assertThat(source).as(file + " must only call the public API surface")
                    .contains("/api/v1/");
        }
        String harness = Files.readString(base.resolve("DailyWorkflowHarnessV2.java"),
                StandardCharsets.UTF_8);
        assertThat(harness).doesNotContain("vault/", "archive/");
    }
}
