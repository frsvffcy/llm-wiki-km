package org.km.llmwiki.ai.answer;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider-free, deterministic Answer Context Compaction evaluation gate
 * ({@code answer-context-compaction-corpus-v1}).
 *
 * <p>Production evidence assembly, authority/currentness admission, and citation identity are
 * NOT touched: the baseline is the production {@link AnswerContextAssembler} itself, and the
 * candidates are evaluation-only fixtures that compact block content on top of the baseline
 * context. Hard invariants (evidence identity drift, citation correctness, hash carry-through,
 * insufficient-evidence semantics) must hold for every case and candidate; supporting-fact
 * retention is compared per case against both the baseline and a declared per-case floor, and
 * every regression is reported individually — aggregate reduction never substitutes for
 * per-case correctness. Provider token usage and end-to-end latency require a live provider
 * and are deliberately NOT measured here; that provider-dependent measurement stays separate
 * from this CI gate.
 */
@Tag("unit")
class AnswerContextCompactionEvaluationTest {

    private static final AnswerContextAssembler BASELINE_ASSEMBLER = new AnswerContextAssembler();
    private static final AnswerContextBudget BUDGET = AnswerContextBudget.DEFAULT;
    private static final Path REPORT_DIRECTORY =
            Path.of("target", "quality-reports");

    @Test
    void evaluatesBaselineAndCandidatesWithPerCaseRegressionVisibility() throws IOException {
        List<AnswerContextCompactionCandidates.Candidate> candidates = List.of(
                AnswerContextCompactionCandidates.headTailWindow(),
                AnswerContextCompactionCandidates.sentenceSkeleton());

        List<CaseReport> reports = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        for (AnswerContextCompactionCorpusV1.CompactionCase compactionCase
                : AnswerContextCompactionCorpusV1.cases()) {
            org.km.llmwiki.rag.EvidenceBundle caseBundle =
                    AnswerContextCompactionCorpusV1.bundle(compactionCase.evidences());
            AnswerContext baseline = BASELINE_ASSEMBLER.assemble(caseBundle, BUDGET);
            int originalCodePoints = baseline.usage().usedCodePoints();
            double baselineRetention = retention(baseline, compactionCase);
            reports.add(new CaseReport(compactionCase.caseId(), "baseline-truncation",
                    originalCodePoints, baseline.usage().usedCodePoints(), baselineRetention,
                    baselineRetention, null, List.of()));

            for (AnswerContextCompactionCandidates.Candidate candidate : candidates) {
                long startedAt = System.nanoTime();
                AnswerContext projected = candidate.project(caseBundle, baseline, BUDGET);
                long overheadNanos = System.nanoTime() - startedAt;

                List<String> caseViolations = invariants(compactionCase, baseline, projected);
                violations.addAll(caseViolations.stream()
                        .map(violation -> compactionCase.caseId() + "/" + candidate.name()
                                + ": " + violation)
                        .toList());
                double retention = retention(projected, compactionCase);
                List<String> regressions = new ArrayList<>();
                for (String fact : compactionCase.supportingFacts()) {
                    boolean inBaseline = containsFact(baseline, fact);
                    boolean inProjected = containsFact(projected, fact);
                    if (inBaseline && !inProjected) {
                        regressions.add("supporting fact lost by candidate: " + fact);
                    }
                }
                // Mandatory cases declare a hard floor; non-mandatory cases may regress and
                // the regression is recorded (NO-OP-if-unsafe is a legitimate outcome).
                if (retention < compactionCase.retentionFloor()) {
                    regressions.add("retention " + retention + " below floor "
                            + compactionCase.retentionFloor());
                    if (compactionCase.mandatory()) {
                        violations.add(compactionCase.caseId() + "/" + candidate.name()
                                + ": mandatory floor violated (" + retention + " < "
                                + compactionCase.retentionFloor() + ")");
                    }
                }
                if (retention < baselineRetention) {
                    regressions.add("retention regressed below baseline: " + retention
                            + " < " + baselineRetention);
                }
                reports.add(new CaseReport(compactionCase.caseId(), candidate.name(),
                        originalCodePoints, projected.usage().usedCodePoints(), retention,
                        baselineRetention, Long.valueOf(overheadNanos), regressions));
            }
        }

        writeReports(reports, violations);

        // Hard, provider-free gate: no evidence-identity/citation/authority drift anywhere,
        // and every mandatory-case regression is individually visible (never only aggregate).
        assertThat(violations)
                .as("per-case correctness regressions (see %s for the full report)"
                        .formatted(reportPath("md")))
                .isEmpty();
    }

    private List<String> invariants(AnswerContextCompactionCorpusV1.CompactionCase compactionCase,
                                    AnswerContext baseline, AnswerContext projected) {
        List<String> violations = new ArrayList<>();
        if (!projected.blocks().stream().map(AnswerContextBlock::citationId).toList()
                .equals(baseline.blocks().stream().map(AnswerContextBlock::citationId).toList())) {
            violations.add("citation id drift");
        }
        if (!projected.blocks().stream().map(AnswerContextBlock::authorityIdentity).toList()
                .equals(baseline.blocks().stream()
                        .map(AnswerContextBlock::authorityIdentity).toList())) {
            violations.add("evidence identity drift");
        }
        for (int index = 0; index < projected.blocks().size(); index++) {
            AnswerContextBlock original = baseline.blocks().get(index);
            AnswerContextBlock block = projected.blocks().get(index);
            if (!block.contentHash().equals(original.contentHash())) {
                violations.add("compacted text must not replace the canonical content hash");
            }
            if (!block.provenance().equals(original.provenance())
                    || block.evidenceKind() != original.evidenceKind()) {
                violations.add("provenance/kind drift");
            }
            if (block.content().isBlank()) {
                violations.add("compaction removed all content for " + block.citationId());
            }
        }
        // Rejected/stale candidates can never re-enter: projected identities must be a
        // subsequence of the baseline's own (already authority-admitted) identities.
        List<String> baselineIdentities = baseline.blocks().stream()
                .map(AnswerContextBlock::authorityIdentity).toList();
        for (AnswerContextBlock block : projected.blocks()) {
            if (!baselineIdentities.contains(block.authorityIdentity())) {
                violations.add("authority violation: unknown identity re-entered "
                        + block.authorityIdentity());
            }
        }
        if (baseline.blocks().isEmpty() != projected.blocks().isEmpty()) {
            violations.add("insufficient-evidence semantic drift");
        }
        return violations;
    }

    static double retention(AnswerContext context,
                                    AnswerContextCompactionCorpusV1.CompactionCase compactionCase) {
        if (compactionCase.supportingFacts().isEmpty()) {
            return 1.0d;
        }
        String full = context.blocks().stream().map(AnswerContextBlock::content)
                .reduce("", (left, right) -> left + "\n" + right);
        long retained = compactionCase.supportingFacts().stream()
                .filter(fact -> full.contains(fact)).count();
        return (double) retained / compactionCase.supportingFacts().size();
    }

    private static boolean containsFact(AnswerContext context, String fact) {
        String full = context.blocks().stream().map(AnswerContextBlock::content)
                .reduce("", (left, right) -> left + "\n" + right);
        return full.contains(fact);
    }

    private void writeReports(List<CaseReport> reports, List<String> violations)
            throws IOException {
        Files.createDirectories(REPORT_DIRECTORY);
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Answer Context Compaction Evaluation\n\n")
                .append("- corpus: ").append(AnswerContextCompactionCorpusV1.VERSION).append('\n')
                .append("- budget: ").append(BUDGET).append('\n')
                .append("- generated: ").append(Instant.now()).append('\n')
                .append("- policy note: provider token usage and end-to-end latency are NOT\n")
                .append("  measured by this provider-free CI gate; code points, reduction, and\n")
                .append("  compaction overhead are the recorded quantities.\n\n")
                .append("| case | policy | baseline cp | projected cp | reduction | retention |")
                .append(" baseline retention | overhead ms | regressions |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        for (CaseReport report : reports) {
            int original = report.originalCodePoints;
            int projected = report.projectedCodePoints;
            double reduction = original == 0 ? 0.0d : 1.0d - (double) projected / original;
            String overhead = report.overheadNanos == null ? "n/a"
                    : String.format(Locale.ROOT, "%.3f", report.overheadNanos / 1_000_000.0d);
            markdown.append(String.format(Locale.ROOT,
                    "| %s | %s | %d | %d | %.3f | %.2f | %.2f | %s | %s |%n",
                    report.caseId, report.policy, original, projected, reduction,
                    report.retention, report.baselineRetention, overhead,
                    report.regressions.isEmpty() ? "—" : String.join("; ", report.regressions)));
        }
        markdown.append("\n## Per-query regression list\n\n");
        if (violations.isEmpty()) {
            markdown.append("(none — all invariants and floors hold)\n");
        } else {
            violations.forEach(violation -> markdown.append("- ").append(violation).append('\n'));
        }
        Files.writeString(REPORT_DIRECTORY.resolve("answer-context-compaction-evaluation-v1.md"),
                markdown.toString(), StandardCharsets.UTF_8);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("corpus", AnswerContextCompactionCorpusV1.VERSION);
        json.put("budget", Map.of("maxEvidenceItems", BUDGET.maxEvidenceItems(),
                "maxCodePointsPerItem", BUDGET.maxCodePointsPerItem(),
                "maxTotalCodePoints", BUDGET.maxTotalCodePoints()));
        json.put("generatedAt", Instant.now().toString());
        json.put("providerTokensMeasured", false);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CaseReport report : reports) {
            int original = report.originalCodePoints;
            int projected = report.projectedCodePoints;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", report.caseId);
            row.put("policy", report.policy);
            row.put("baselineCodePoints", original);
            row.put("projectedCodePoints", projected);
            row.put("reductionRatio", original == 0 ? 0.0d
                    : 1.0d - (double) projected / original);
            row.put("supportingFactRetention", report.retention);
            row.put("baselineRetention", report.baselineRetention);
            row.put("compactionOverheadNanos", report.overheadNanos);
            row.put("regressions", report.regressions);
            rows.add(row);
        }
        json.put("cases", rows);
        json.put("violations", violations);
        Files.writeString(REPORT_DIRECTORY.resolve("answer-context-compaction-evaluation-v1.json"),
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(json),
                StandardCharsets.UTF_8);
    }

    private Path reportPath(String suffix) {
        return REPORT_DIRECTORY.resolve("answer-context-compaction-evaluation-v1." + suffix);
    }

    private record CaseReport(String caseId, String policy, int originalCodePoints,
                              int projectedCodePoints, double retention,
                              double baselineRetention, Long overheadNanos,
                              List<String> regressions) {
    }
}
