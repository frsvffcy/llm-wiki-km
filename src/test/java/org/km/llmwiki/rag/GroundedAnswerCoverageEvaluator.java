package org.km.llmwiki.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.km.llmwiki.ai.answer.AnswerContext;
import org.km.llmwiki.ai.answer.AnswerResult;
import org.km.llmwiki.ai.answer.GroundedAnswerResponse;
import org.km.llmwiki.ai.ask.AskResult;

/**
 * Test-only citation-coverage evaluator for Issue #551.
 *
 * <p>Reuses the #546 required-set truth
 * ({@link RagQueryShapeCoverageCorpusV1}) without creating a second authority. Retrieval
 * coverage ({@link RagQueryShapeCoverageCorpusV1.CoverageAssessment}) and answer citation
 * coverage are reported separately so {@code retrieval found both + answer cites only one}
 * is distinguishable from {@code retrieval found both + answer cites both}.
 *
 * <p>Conflict text quality is explicitly {@code UNOBSERVED} in this story. Keyword matching
 * must not be used as a semantic correctness authority; the only measured signal here is
 * required-citation coverage.
 */
final class GroundedAnswerCoverageEvaluator {

    private static final String CONFLICT_TEXT_DETAIL =
            "conflict text quality is UNOBSERVED: no repository-owned semantic judge, "
                    + "human rubric, or controlled live-provider run in this harness; "
                    + "keyword matching is forbidden as a correctness authority";

    private GroundedAnswerCoverageEvaluator() {
    }

    record Assessment(
            GroundedAnswerCoverageVerdict verdict,
            int requiredCount,
            int citedRequiredCount,
            double requiredRecall,
            List<String> missingRequired,
            List<String> unknownCitations,
            boolean providerInsufficientEvidence,
            boolean retrievalComplete,
            List<String> retrievalMissing,
            EvaluationTrustContract.EvidenceStrength citationEvidenceStrength,
            EvaluationTrustContract.EvidenceStrength conflictTextQuality,
            String conflictTextDetail) {
        Assessment {
            missingRequired = List.copyOf(missingRequired);
            unknownCitations = List.copyOf(unknownCitations);
            retrievalMissing = List.copyOf(retrievalMissing);
        }
    }

    static Assessment assess(
            RagQueryShapeCoverageCorpusV1.GoldenCase golden,
            RagQueryShapeCoverageCorpusV1.CoverageAssessment retrievalCoverage,
            AnswerContext context,
            List<String> citedEvidenceIds,
            boolean insufficientEvidence) {
        if (golden == null || retrievalCoverage == null || context == null || citedEvidenceIds == null) {
            int requiredCount = golden == null ? 0 : golden.requiredEvidence().size();
            return new Assessment(
                    GroundedAnswerCoverageVerdict.UNOBSERVED,
                    requiredCount,
                    0,
                    0.0d,
                    golden == null
                            ? List.of()
                            : golden.requiredEvidence().stream().sorted().toList(),
                    List.of(),
                    insufficientEvidence,
                    false,
                    retrievalCoverage == null ? List.of() : retrievalCoverage.missingEvidence(),
                    EvaluationTrustContract.EvidenceStrength.UNOBSERVED,
                    EvaluationTrustContract.EvidenceStrength.UNOBSERVED,
                    CONFLICT_TEXT_DETAIL + " (missing evaluation inputs)");
        }

        Set<String> required = Set.copyOf(golden.requiredEvidence());
        Map<String, String> citationToAuthority = context.blocks().stream()
                .collect(Collectors.toMap(
                        block -> block.citationId(), block -> block.authorityIdentity(),
                        (first, second) -> first));

        if (insufficientEvidence) {
            if (!citedEvidenceIds.isEmpty()) {
                return new Assessment(
                        GroundedAnswerCoverageVerdict.INVALID,
                        required.size(),
                        0,
                        0.0d,
                        required.stream().sorted().toList(),
                        List.of(),
                        true,
                        retrievalCoverage.complete(),
                        retrievalCoverage.missingEvidence(),
                        EvaluationTrustContract.EvidenceStrength.MEASURED,
                        EvaluationTrustContract.EvidenceStrength.UNOBSERVED,
                        CONFLICT_TEXT_DETAIL);
            }
            return new Assessment(
                    GroundedAnswerCoverageVerdict.ABSTAINED,
                    required.size(),
                    0,
                    0.0d,
                    required.stream().sorted().toList(),
                    List.of(),
                    true,
                    retrievalCoverage.complete(),
                    retrievalCoverage.missingEvidence(),
                    EvaluationTrustContract.EvidenceStrength.MEASURED,
                    EvaluationTrustContract.EvidenceStrength.UNOBSERVED,
                    CONFLICT_TEXT_DETAIL);
        }

        if (citedEvidenceIds.isEmpty()) {
            return new Assessment(
                    GroundedAnswerCoverageVerdict.INVALID,
                    required.size(),
                    0,
                    0.0d,
                    required.stream().sorted().toList(),
                    List.of(),
                    false,
                    retrievalCoverage.complete(),
                    retrievalCoverage.missingEvidence(),
                    EvaluationTrustContract.EvidenceStrength.MEASURED,
                    EvaluationTrustContract.EvidenceStrength.UNOBSERVED,
                    CONFLICT_TEXT_DETAIL);
        }

        List<String> deduplicated = new ArrayList<>(new LinkedHashSet<>(citedEvidenceIds));
        List<String> unknown = deduplicated.stream()
                .filter(id -> !citationToAuthority.containsKey(id))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            Set<String> citedAuthority = deduplicated.stream()
                    .filter(citationToAuthority::containsKey)
                    .map(citationToAuthority::get)
                    .collect(Collectors.toSet());
            Set<String> citedRequired = new TreeSet<>(citedAuthority);
            citedRequired.retainAll(required);
            List<String> missing = required.stream()
                    .filter(identity -> !citedRequired.contains(identity))
                    .sorted()
                    .toList();
            return new Assessment(
                    GroundedAnswerCoverageVerdict.INVALID,
                    required.size(),
                    citedRequired.size(),
                    recall(citedRequired.size(), required.size()),
                    missing,
                    unknown,
                    false,
                    retrievalCoverage.complete(),
                    retrievalCoverage.missingEvidence(),
                    EvaluationTrustContract.EvidenceStrength.MEASURED,
                    EvaluationTrustContract.EvidenceStrength.UNOBSERVED,
                    CONFLICT_TEXT_DETAIL);
        }

        Set<String> citedAuthority = deduplicated.stream()
                .map(citationToAuthority::get)
                .collect(Collectors.toSet());
        Set<String> citedRequired = new TreeSet<>(citedAuthority);
        citedRequired.retainAll(required);
        List<String> missing = required.stream()
                .filter(identity -> !citedRequired.contains(identity))
                .sorted()
                .toList();
        GroundedAnswerCoverageVerdict verdict =
                missing.isEmpty()
                        ? GroundedAnswerCoverageVerdict.COMPLETE
                        : GroundedAnswerCoverageVerdict.PARTIAL;
        return new Assessment(
                verdict,
                required.size(),
                citedRequired.size(),
                recall(citedRequired.size(), required.size()),
                missing,
                List.of(),
                false,
                retrievalCoverage.complete(),
                retrievalCoverage.missingEvidence(),
                EvaluationTrustContract.EvidenceStrength.MEASURED,
                EvaluationTrustContract.EvidenceStrength.UNOBSERVED,
                CONFLICT_TEXT_DETAIL);
    }

    static Assessment assess(
            RagQueryShapeCoverageCorpusV1.GoldenCase golden,
            RagQueryShapeCoverageCorpusV1.CoverageAssessment retrievalCoverage,
            AnswerContext context,
            GroundedAnswerResponse response) {
        if (response == null) {
            return assess(golden, retrievalCoverage, context, (List<String>) null, false);
        }
        return assess(
                golden, retrievalCoverage, context,
                response.citedEvidenceIds(), response.insufficientEvidence());
    }

    static Assessment assess(
            RagQueryShapeCoverageCorpusV1.GoldenCase golden,
            RagQueryShapeCoverageCorpusV1.CoverageAssessment retrievalCoverage,
            AnswerContext context,
            AnswerResult result) {
        if (result == null) {
            return assess(golden, retrievalCoverage, context, (List<String>) null, false);
        }
        return assess(
                golden, retrievalCoverage, context,
                result.citedEvidenceIds(), result.insufficientEvidence());
    }

    static Assessment assess(
            RagQueryShapeCoverageCorpusV1.GoldenCase golden,
            RagQueryShapeCoverageCorpusV1.CoverageAssessment retrievalCoverage,
            AnswerContext context,
            AskResult askResult) {
        if (askResult == null) {
            return assess(golden, retrievalCoverage, context, (List<String>) null, false);
        }
        if (askResult.insufficientEvidence()) {
            return assess(golden, retrievalCoverage, context, List.of(), true);
        }
        List<String> cited = askResult.citations().stream()
                .map(citation -> citation.citationId())
                .toList();
        return assess(golden, retrievalCoverage, context, cited, false);
    }

    private static double recall(int citedRequired, int requiredTotal) {
        if (requiredTotal <= 0) {
            return 0.0d;
        }
        return ((double) citedRequired) / ((double) requiredTotal);
    }
}
