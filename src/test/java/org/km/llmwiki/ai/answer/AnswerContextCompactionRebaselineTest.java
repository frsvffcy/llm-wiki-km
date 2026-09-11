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
            // A per-case discriminating query makes the exact-anchor policy actually reorder
            // the multi-item cases (the corpus helper's constant query would tie everything
            // and silently exercise nothing); the first declared supporting fact's tokens
            // anchor the relevant evidence, matching real ask traffic.
            String discriminatingQuery = compactionCase.supportingFacts().isEmpty()
                    ? "compaction evaluation" : compactionCase.supportingFacts().get(0);
            EvidenceBundle original =
                    AnswerContextCompactionCorpusV1.bundleWithQuery(compactionCase.evidences(),
                            discriminatingQuery);

            AnswerContext baseline =
                    BASELINE_ASSEMBLER.assemble(original, BUDGET);
            RerankResult rerank = PRODUCTION_RERANK.apply(original);
            AnswerContext reranked =
                    BASELINE_ASSEMBLER.assemble(rerank.orderedBundle(), BUDGET);

            // Identity invariants hold for the reranked packing input.
            assertThat(rerank.status()).isIn(RerankStatus.APPLIED,
                    RerankStatus.NO_OP_INSUFFICIENT_CANDIDATES);
            assertThat(rerank.orderedBundle().items().stream()
                    .map(item -> item.stableIdentity()).sorted().toList())
                    .isEqualTo(original.items().stream().map(item -> item.stableIdentity())
                            .sorted().toList());
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
    void truncationPressureInteractionIsExercisedAndDoesNotRegress() {
        // Real falsification fixture: five long items whose supporting facts sit at the TAIL
        // of each item's content. Under the DEFAULT budget the total window can hold only
        // ~3.5 such items, so the reorder genuinely changes which items make it into the
        // packed context (the corpus helper alone would tie on the constant query and
        // silently exercise nothing). The query anchors the LAST item's tail fact: the
        // exact-anchor reorder must pull that item into the window — proving the
        // reorder/truncation interaction is real and that the gate observes it.
        String anchoredFact = "Only the final worker owns the shutdown handshake.";
        String discriminatingQuery = "Who owns the shutdown handshake?";
        var items = new java.util.ArrayList<org.km.llmwiki.rag.EvidenceItem>();
        items.add(AnswerContextCompactionCorpusV1.exposedWiki("pressure-a", "Quorum Notes",
                "vault/quorum-a.md", " unrelated quorum background ".repeat(180)
                        + " unrelated quorum tail sentence."));
        items.add(AnswerContextCompactionCorpusV1.exposedWiki("pressure-b", "Lock Design",
                "vault/lock-b.md", " unrelated lock background ".repeat(190)
                        + " unrelated lock tail sentence."));
        items.add(AnswerContextCompactionCorpusV1.exposedWiki("pressure-c", "Cache Notes",
                "vault/cache-c.md", " unrelated cache background ".repeat(180)
                        + " unrelated cache tail sentence."));
        items.add(AnswerContextCompactionCorpusV1.exposedWiki("pressure-d", "Deploy Notes",
                "vault/deploy-d.md", " unrelated deploy background ".repeat(180)
                        + " unrelated deploy tail sentence."));
        items.add(AnswerContextCompactionCorpusV1.exposedWiki("pressure-e", "Shutdown Notes",
                "vault/shutdown-e.md", " shutdown background ".repeat(185)
                        + " " + anchoredFact));
        EvidenceBundle original =
                AnswerContextCompactionCorpusV1.bundleWithQuery(items, discriminatingQuery);

        org.km.llmwiki.rag.RerankResult rerank = PRODUCTION_RERANK.apply(original);
        assertThat(rerank.status()).isEqualTo(org.km.llmwiki.rag.RerankStatus.APPLIED);
        // The reorder must actually engage: the anchored item moves to the front.
        assertThat(rerank.orderedBundle().items().stream().map(item -> item.stableId().strip())
                .findFirst().orElseThrow()).isEqualTo("pressure-e");

        AnswerContext baseline = BASELINE_ASSEMBLER.assemble(original, BUDGET);
        AnswerContext reranked =
                BASELINE_ASSEMBLER.assemble(rerank.orderedBundle(), BUDGET);

        boolean baselineHasFact = baseline.blocks().stream()
                .anyMatch(block -> block.content().contains(anchoredFact));
        boolean rerankedHasFact = reranked.blocks().stream()
                .anyMatch(block -> block.content().contains(anchoredFact));
        // The reorder/truncation interaction is real: the exact-anchor reorder brings the
        // anchored item into the packed window (assert which side misses the fact so a
        // fixture change is diagnosable instead of a bare boolean).
        org.assertj.core.api.Assertions.assertThat(rerankedHasFact)
                .as("reranked packing should contain the anchored item's tail fact "
                        + "(baseline=%s, reranked identities=%s)".formatted(baselineHasFact,
                        reranked.blocks().stream().map(AnswerContextBlock::authorityIdentity)
                                .toList()))
                .isTrue();
        // The gated quality direction: the packed supporting-fact retention under the
        // reranked order is at least the no-rerank baseline's.
        var declaredCase = new AnswerContextCompactionCorpusV1.CompactionCase(
                "pressure", java.util.Set.of(), true, java.util.List.of(anchoredFact),
                java.util.List.of(), 1.0d, "re-baseline falsification fixture");
        assertThat(AnswerContextCompactionEvaluationTest.retention(reranked, declaredCase))
                .isGreaterThanOrEqualTo(
                        AnswerContextCompactionEvaluationTest.retention(baseline, declaredCase));
        // NOTE: the packed CONTEXT block count legitimately differs across orderings when the
        // total budget truncation boundary moves (baseline drops the last item, the reranked
        // order fills the window with the anchored item first). The identity-set invariant is
        // enforced at the rerank boundary (bundle level, asserted above), not at the
        // budget-truncated packing output.
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
