package org.km.llmwiki.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Versioned deterministic query-shape truth for Issue #546.
 *
 * <p>This corpus is intentionally small. It does not model answer generation and does not
 * introduce a semantic-sufficiency runtime. It only states which canonical evidence identities
 * must be present before an evaluation may call a conflict/completeness scenario covered.
 */
final class RagQueryShapeCoverageCorpusV1 {

    static final String VERSION = "rag-query-shape-coverage-v1";

    static final String CONFLICTING_INFO = "conflicting-info";
    static final String COMPLETENESS_REQUIRED = "completeness-required";

    private static final String WIKI = "WIKI:";

    static final String RELEASE_ORIGINAL = "wiki-release-date-oct-01";
    static final String RELEASE_DELAYED = "wiki-release-date-oct-15";
    static final String DEPLOY_MIGRATION = "wiki-deploy-db-migration";
    static final String DEPLOY_ROLLBACK = "wiki-deploy-backup-rollback";

    record GoldenCase(String id, String queryClass, String text, Set<String> requiredEvidence) {
        GoldenCase {
            requiredEvidence = Set.copyOf(requiredEvidence);
        }
    }

    record CoverageAssessment(boolean anyRequiredEvidence, boolean complete,
                              List<String> missingEvidence) {
        CoverageAssessment {
            missingEvidence = List.copyOf(missingEvidence);
        }
    }

    List<GraphRetrievalGoldenCorpus.GoldenPage> pages() {
        return List.of(
                new GraphRetrievalGoldenCorpus.GoldenPage(
                        RELEASE_ORIGINAL,
                        "正式上線原公告",
                        "產品正式上線日期為 10 月 1 日，依原公告執行。",
                        List.of("release", "announcement"),
                        false),
                new GraphRetrievalGoldenCorpus.GoldenPage(
                        RELEASE_DELAYED,
                        "正式上線延期公告",
                        "產品正式上線日期已延期，新的正式上線日期為 10 月 15 日。",
                        List.of("release", "announcement"),
                        false),
                new GraphRetrievalGoldenCorpus.GoldenPage(
                        DEPLOY_MIGRATION,
                        "部署檢查資料庫",
                        "部署檢查必須完成資料庫 migration，確認 schema 已正確套用。",
                        List.of("deployment", "database"),
                        false),
                new GraphRetrievalGoldenCorpus.GoldenPage(
                        DEPLOY_ROLLBACK,
                        "部署檢查回復",
                        "部署檢查必須完成備份與 rollback 演練，確認失敗時可以安全回復。",
                        List.of("deployment", "recovery"),
                        false));
    }

    List<GoldenCase> cases() {
        return List.of(
                new GoldenCase(
                        CONFLICTING_INFO,
                        "CONFLICTING_INFO",
                        "產品正式上線日期",
                        Set.of(WIKI + RELEASE_ORIGINAL, WIKI + RELEASE_DELAYED)),
                new GoldenCase(
                        COMPLETENESS_REQUIRED,
                        "COMPLETENESS_REQUIRED",
                        "部署檢查必須完成",
                        Set.of(WIKI + DEPLOY_MIGRATION, WIKI + DEPLOY_ROLLBACK)));
    }

    GoldenCase caseById(String id) {
        return cases().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    CoverageAssessment assess(GoldenCase golden, List<String> retrieved) {
        Set<String> actual = Set.copyOf(retrieved);
        List<String> missing = golden.requiredEvidence().stream()
                .filter(identity -> !actual.contains(identity))
                .sorted()
                .toList();
        boolean anyRequiredEvidence = golden.requiredEvidence().stream().anyMatch(actual::contains);
        return new CoverageAssessment(anyRequiredEvidence, missing.isEmpty(), missing);
    }

    /**
     * INFO_NOT_FOUND intentionally reuses the existing Query Transformation no-evidence case.
     * Issue #546 must not create a parallel truth for a shape already owned elsewhere.
     */
    String infoNotFoundBaselineQueryId() {
        return QueryTransformationEvaluationCorpusV1.NO_EVIDENCE_QUERY_ID;
    }

    String fingerprint() {
        List<String> parts = new ArrayList<>();
        parts.add(VERSION);
        pages().stream()
                .sorted(Comparator.comparing(GraphRetrievalGoldenCorpus.GoldenPage::knowledgeId))
                .forEach(page -> parts.add(page.knowledgeId() + "|" + page.title() + "|"
                        + page.body() + "|" + page.tags().stream().sorted().toList()));
        cases().stream().sorted(Comparator.comparing(GoldenCase::id)).forEach(golden ->
                parts.add(golden.id() + "|" + golden.queryClass() + "|" + golden.text() + "|"
                        + golden.requiredEvidence().stream().sorted().toList()));
        parts.add("INFO_NOT_FOUND->" + infoNotFoundBaselineQueryId());
        return EvaluationTrustContract.fingerprint(parts);
    }
}
