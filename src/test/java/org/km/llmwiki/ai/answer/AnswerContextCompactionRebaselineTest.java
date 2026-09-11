package org.km.llmwiki.ai.answer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.rag.EvidenceBundle;
import org.km.llmwiki.rag.ExactAnchorRerankPolicyV1;
import org.km.llmwiki.rag.RerankResult;
import org.km.llmwiki.rag.RerankStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #308 compaction re-baseline gate for the rerank adoption (#326): the production second-stage
 * policy reorders the evidence BEFORE context packing, so the #308 corpus must be re-run with
 * the reranked ordering to prove the change of evidence order introduces no supporting-fact or
 * citation regression. The production {@code EvidenceContextProjector} stays the only packing
 * path with the {@code context-policy-v1-current} baseline semantics; if any mandatory case
 * regresses under the reranked ordering, the production default promotion must be NO-GO.
 */
@Tag("unit")
class AnswerContextCompactionRebaselineTest {

    private static final AnswerContextAssembler BASELINE_ASSEMBLER = new AnswerContextAssembler();
    private static final AnswerContextBudget BUDGET = AnswerContextBudget.DEFAULT;
    private static final ExactAnchorRerankPolicyV1 PRODUCTION_RERANK =
            new ExactAnchorRerankPolicyV1();

    @Test
    void rerankedEvidenceOrderingIntroducesNoMandatoryCaseRegression() {
        for (AnswerContextCompactionCorpusV1.CompactionCase compactionCase
                : AnswerContextCompactionCorpusV1.cases()) {
            EvidenceBundle original =
                    AnswerContextCompactionCorpusV1.bundle(compactionCase.evidences());

            AnswerContext baseline =
                    BASELINE_ASSEMBLER.assemble(original, BUDGET);
            RerankResult rerank = PRODUCTION_RERANK.apply(original);
            AnswerContext reranked =
                    BASELINE_ASSEMBLER.assemble(rerank.orderedBundle(), BUDGET);

            // Identity invariants hold for the reranked packing input.
            assertThat(rerank.status()).isIn(RerankStatus.APPLIED,
                    RerankStatus.NO_OP_INSUFFICIENT_CANDIDATES);
            assertThat(reranked.blocks().stream().map(AnswerContextBlock::authorityIdentity)
                    .sorted().toList())
                    .isEqualTo(baseline.blocks().stream()
                            .map(AnswerContextBlock::authorityIdentity)
                            .sorted().toList());

            // The re-baseline gate is per-case no-regression against the current default
            // ordering: reranking changes only the packing ORDER, so every case must retain
            // at least as much supporting-fact quality as the no-rerank baseline (the #308
            // mandatory floors are owned by the compaction-candidate gate itself; the
            // baseline assembler truncation semantics are unchanged by this adoption).
            double baselineRetention =
                    AnswerContextCompactionEvaluationTest.retention(baseline, compactionCase);
            double rerankedRetention =
                    AnswerContextCompactionEvaluationTest.retention(reranked, compactionCase);
            assertThat(rerankedRetention)
                    .as("case %s: reranked packing order must not regress supporting-fact "
                            + "retention (%s < %s)".formatted(compactionCase.caseId(),
                            rerankedRetention, baselineRetention))
                    .isGreaterThanOrEqualTo(baselineRetention);
        }
    }

    @Test
    void rerankedPackingNeverSilentlyMixesCompactionPolicies() {
        for (AnswerContextCompactionCorpusV1.CompactionCase compactionCase
                : AnswerContextCompactionCorpusV1.cases()) {
            EvidenceBundle original =
                    AnswerContextCompactionCorpusV1.bundle(compactionCase.evidences());
            RerankResult rerank = PRODUCTION_RERANK.apply(original);

            // The rerank boundary is not a compaction policy: block content semantics stay
            // byte-equivalent per evidence identity; only the ORDER may differ.
            AnswerContext baseline = BASELINE_ASSEMBLER.assemble(original, BUDGET);
            AnswerContext reranked =
                    BASELINE_ASSEMBLER.assemble(rerank.orderedBundle(), BUDGET);

            var baselineByIdentity = baseline.blocks().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            AnswerContextBlock::authorityIdentity, block -> block));
            for (AnswerContextBlock block : reranked.blocks()) {
                AnswerContextBlock baselineBlock = baselineByIdentity.get(
                        block.authorityIdentity());
                assertThat(baselineBlock).isNotNull();
                // Same canonical block identity, hash, provenance, and content — the rerank
                // reordering never changes how each evidence item is packed, only when.
                assertThat(block.contentHash()).isEqualTo(baselineBlock.contentHash());
                assertThat(block.provenance()).isEqualTo(baselineBlock.provenance());
                assertThat(block.evidenceKind()).isEqualTo(baselineBlock.evidenceKind());
                assertThat(block.content()).isEqualTo(baselineBlock.content());
            }
        }
    }
}
