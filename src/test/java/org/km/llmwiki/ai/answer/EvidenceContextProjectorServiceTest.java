package org.km.llmwiki.ai.answer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.rag.EvidenceBundle;
import org.km.llmwiki.rag.EvidenceBudget;
import org.km.llmwiki.rag.EvidenceItem;
import org.km.llmwiki.rag.EvidenceKind;
import org.km.llmwiki.rag.EvidenceWorkspace;
import org.km.llmwiki.rag.RetrievalDiagnostics;
import org.km.llmwiki.rag.RetrievalMode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Holds the single production context-packing boundary: identity-preserving projection with
 * the active versioned policy, hard invariant revalidation (identity drift, citation drift,
 * budget growth, dishonest truncation flags all fail closed), and deterministic typed
 * fallback to the bounded baseline. Evidence authority is never touched.
 */
@Tag("unit")
class EvidenceContextProjectorServiceTest {

    private static final EvidenceWorkspace WORKSPACE = new EvidenceWorkspace(7, "Projection");
    private static final AnswerContextBudget BUDGET = new AnswerContextBudget(8, 50, 200);

    private final EvidenceContextProjectorService projector = new EvidenceContextProjectorService(
            new AnswerContextAssembler(),
            new AnswerContextCompactionPolicyRegistry(List.of(new ContextPolicyV1Current()),
                    ContextPolicyV1Current.VERSION));

    @Test
    void currentPolicyProjectsTheBaselineIdentityWithCompleteMetadata() {
        EvidenceBundle bundle = bundle(List.of(
                wiki("one", "One", "vault/one.md", "First evidence fact"),
                source(41L, 900L, "design.pdf", "Second evidence fact")));

        ContextProjectionResult result = projector.project(bundle, BUDGET);

        AnswerContext baseline = new AnswerContextAssembler().assemble(bundle, BUDGET);
        assertThat(result.policyVersion()).isEqualTo("context-policy-v1-current");
        assertThat(result.context().blocks())
                .containsExactlyElementsOf(baseline.blocks());
        assertThat(result.context().usage().usedCodePoints())
                .isEqualTo(baseline.usage().usedCodePoints());
        assertThat(result.originalEvidenceCount()).isEqualTo(2);
        assertThat(result.projectedEvidenceCount()).isEqualTo(2);
        assertThat(result.originalCodePoints()).isEqualTo(39);
        assertThat(result.baselineCodePoints()).isEqualTo(result.projectedCodePoints());
        assertThat(result.projectedCodePoints())
                .isEqualTo(result.context().usage().usedCodePoints());
        assertThat(result.reductionRatio()).isZero();
        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.failureType()).isNull();
        assertThat(result.blocks()).extracting(ProjectedEvidenceBlock::citationId)
                .containsExactly("E1", "E2");
        assertThat(result.blocks()).extracting(ProjectedEvidenceBlock::projectionKind)
                .containsOnly(ProjectionKind.VERBATIM);
        assertThat(result.blocks()).allSatisfy(block -> {
            assertThat(block.contentCompacted()).isFalse();
            assertThat(block.projectedCodePoints()).isEqualTo(block.originalCodePoints());
        });
    }

    @Test
    void projectionIsDeterministicForTheSameInputAndPolicy() {
        EvidenceBundle bundle = bundle(List.of(wiki("one", "One", "vault/one.md", "fact")));

        ContextProjectionResult first = projector.project(bundle, BUDGET);
        ContextProjectionResult second = projector.project(bundle, BUDGET);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void truncatingBaselineIsReflectedInKindsAndReduction() {
        EvidenceBundle bundle = bundle(List.of(wiki("long", "Long", "vault/long.md",
                "fact ".repeat(60))));
        AnswerContextBudget budget = new AnswerContextBudget(8, 20, 40);

        ContextProjectionResult result = projector.project(bundle, budget);

        assertThat(result.context().blocks()).hasSize(1);
        assertThat(result.context().usage().truncated()).isTrue();
        assertThat(result.blocks()).extracting(ProjectedEvidenceBlock::projectionKind)
                .containsExactly(ProjectionKind.TRUNCATED);
        assertThat(result.projectedCodePoints()).isEqualTo(result.baselineCodePoints());
        assertThat(result.reductionRatio()).isZero();
    }

    @Test
    void explicitExtractivePolicyCompactsWithinBaselineAndReportsTypedKind() {
        EvidenceBundle bundle = bundle(List.of(wiki("one", "One", "vault/one.md",
                "First sentence stays. " + "filler ".repeat(40) + "Last sentence stays.")));
        AnswerContextBudget budget = new AnswerContextBudget(8, 40, 200);

        ContextProjectionResult result = projector.project(bundle, budget,
                halvingPolicy(ProjectionKind.EXTRACTIVE));

        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.policyVersion()).isEqualTo("test-extractive-halving");
        assertThat(result.blocks()).extracting(ProjectedEvidenceBlock::projectionKind)
                .containsExactly(ProjectionKind.EXTRACTIVE);
        assertThat(result.blocks()).extracting(ProjectedEvidenceBlock::contentCompacted)
                .containsExactly(true);
        assertThat(result.projectedCodePoints()).isLessThan(result.baselineCodePoints());
        assertThat(result.reductionRatio()).isBetween(0.0d, 1.0d);
        assertThat(result.context().blocks().get(0).contentHash())
                .isEqualTo(new AnswerContextAssembler().assemble(bundle, budget)
                        .blocks().get(0).contentHash());
    }

    @Test
    void identityDriftInPolicyOutputFailsClosedToTheBoundedBaseline() {
        EvidenceBundle bundle = bundle(List.of(wiki("one", "One", "vault/one.md", "fact")));

        ContextProjectionResult result = projector.project(bundle, BUDGET,
                shiftedIdentityPolicy("WIKI:other"));

        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.failureType())
                .isEqualTo(ContextProjectionFailureType.PROJECTION_INVARIANT_VIOLATION);
        assertThat(result.context().blocks())
                .containsExactlyElementsOf(new AnswerContextAssembler().assemble(bundle,
                        BUDGET).blocks());
        assertThat(result.reductionRatio()).isZero();
    }

    @Test
    void addedPolicyBlocksFailClosed() {
        EvidenceBundle bundle = bundle(List.of(wiki("one", "One", "vault/one.md", "fact")));

        ContextProjectionResult result = projector.project(bundle, BUDGET, extraBlockPolicy());

        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.failureType())
                .isEqualTo(ContextProjectionFailureType.PROJECTION_INVARIANT_VIOLATION);
        assertThat(result.context().blocks()).hasSize(1);
        assertThat(result.projectedEvidenceCount()).isEqualTo(1);
    }

    @Test
    void budgetGrowthInPolicyOutputIsTypedAsLimitExceededAndFallsBack() {
        EvidenceBundle bundle = bundle(List.of(wiki("one", "One", "vault/one.md", "fact")));

        ContextProjectionResult result = projector.project(bundle, BUDGET,
                grownContentPolicy());

        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.failureType())
                .isEqualTo(ContextProjectionFailureType.PROJECTION_LIMIT_EXCEEDED);
        assertThat(result.context().usage().usedCodePoints())
                .isLessThanOrEqualTo(BUDGET.maxTotalCodePoints());
    }

    @Test
    void dishonestTruncationFlagsFailClosed() {
        EvidenceBundle bundle = bundle(List.of(wiki("long", "Long", "vault/long.md",
                "fact ".repeat(30))));
        AnswerContextBudget budget = new AnswerContextBudget(8, 20, 40);

        ContextProjectionResult result = projector.project(bundle, budget,
                sameContentWithoutFlagsPolicy());

        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.failureType())
                .isEqualTo(ContextProjectionFailureType.PROJECTION_INVARIANT_VIOLATION);
    }

    @Test
    void nullPolicyProjectionFailsClosedAsInvalidPolicy() {
        EvidenceBundle bundle = bundle(List.of(wiki("one", "One", "vault/one.md", "fact")));

        ContextProjectionResult result = projector.project(bundle, BUDGET,
                new AnswerContextCompactionPolicy() {
                    @Override
                    public String version() {
                        return "test-null-projection";
                    }

                    @Override
                    public PolicyProjection project(EvidenceBundle evidence,
                                                    AnswerContext baseline,
                                                    AnswerContextBudget budget) {
                        return null;
                    }
                });

        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.failureType())
                .isEqualTo(ContextProjectionFailureType.INVALID_POLICY);
    }

    @Test
    void nullEvidenceAndBudgetFailFastAsCallerContractViolations() {
        assertThatThrownBy(() -> projector.project(null, BUDGET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence bundle must not be null");
        assertThatThrownBy(() -> projector.project(bundle(List.of(wiki("one", "One",
                "vault/one.md", "fact"))), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context budget must not be null");
    }

    @Test
    void nullPolicyFailsFastAsACallerContractViolation() {
        EvidenceBundle bundle = bundle(List.of(wiki("one", "One", "vault/one.md", "fact")));

        assertThatThrownBy(() -> projector.project(bundle, BUDGET, null))
                .isInstanceOf(AnswerContextProjectionException.class)
                .satisfies(exception -> assertThat(
                        ((AnswerContextProjectionException) exception).failureType())
                        .isEqualTo(ContextProjectionFailureType.INVALID_POLICY));
    }

    @Test
    void blankVersionPolicyFailsClosedToTheBoundedBaseline() {
        EvidenceBundle bundle = bundle(List.of(wiki("one", "One", "vault/one.md", "fact")));

        ContextProjectionResult result = projector.project(bundle, BUDGET, blankVersionPolicy());

        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.failureType())
                .isEqualTo(ContextProjectionFailureType.INVALID_POLICY);
        assertThat(result.policyVersion()).isEqualTo("unknown");
        assertThat(result.context().blocks())
                .containsExactlyElementsOf(new AnswerContextAssembler().assemble(bundle,
                        BUDGET).blocks());
    }

    @Test
    void droppedEvidenceKeepsTheBaselineTruncatedFlagInProjectionMetadata() {
        List<EvidenceItem> items = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            items.add(wiki("item-" + index, "Item " + index, "vault/item-" + index + ".md",
                    "fact ".repeat(800)));
        }
        EvidenceBundle bundle = bundle(items);
        AnswerContextBudget budget = AnswerContextBudget.DEFAULT;

        AnswerContext baseline = new AnswerContextAssembler().assemble(bundle, budget);
        assertThat(baseline.usage().truncated()).isTrue();
        assertThat(baseline.blocks()).allSatisfy(
                block -> assertThat(block.contentTruncated()).isFalse());

        ContextProjectionResult result = projector.project(bundle, budget);

        assertThat(result.context().blocks()).hasSize(4);
        assertThat(result.context().usage().truncated())
                .as("dropped evidence must keep the baseline truncated flag visible")
                .isTrue();
        assertThat(result.blocks()).allSatisfy(block ->
                assertThat(block.contentCompacted()).isFalse());
    }

    @Test
    void mislabeledProjectionKindsFailClosed() {
        EvidenceBundle bundle = bundle(List.of(wiki("one", "One", "vault/one.md",
                "First sentence. " + "filler ".repeat(60) + "Last sentence.")));
        AnswerContextBudget budget = new AnswerContextBudget(8, 40, 200);

        ContextProjectionResult verbatimOnCompacted = projector.project(bundle, budget,
                halvingPolicy(ProjectionKind.VERBATIM));
        ContextProjectionResult extractiveOnIntact = projector.project(bundle, budget,
                identityPolicy(ProjectionKind.EXTRACTIVE));

        assertThat(verbatimOnCompacted.fallbackUsed()).isTrue();
        assertThat(verbatimOnCompacted.failureType())
                .isEqualTo(ContextProjectionFailureType.PROJECTION_INVARIANT_VIOLATION);
        assertThat(extractiveOnIntact.fallbackUsed()).isTrue();
        assertThat(extractiveOnIntact.failureType())
                .isEqualTo(ContextProjectionFailureType.PROJECTION_INVARIANT_VIOLATION);
    }

    @Test
    void policyRuntimeFailureFallsBackToTheBoundedBaseline() {
        EvidenceBundle bundle = bundle(List.of(wiki("one", "One", "vault/one.md", "fact")));

        ContextProjectionResult result = projector.project(bundle, BUDGET,
                new AnswerContextCompactionPolicy() {
                    @Override
                    public String version() {
                        return "test-throws";
                    }

                    @Override
                    public PolicyProjection project(EvidenceBundle evidence,
                                                    AnswerContext baseline,
                                                    AnswerContextBudget budget) {
                        throw new IllegalStateException("hostile internal failure: secret-token");
                    }
                });

        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.failureType())
                .isEqualTo(ContextProjectionFailureType.INVALID_POLICY);
        assertThat(result.context().blocks())
                .containsExactlyElementsOf(new AnswerContextAssembler().assemble(bundle,
                        BUDGET).blocks());
    }

    @Test
    void insufficientEvidenceKeepsTheEmptyProjectionSemantics() {
        EvidenceBundle bundle = bundle(List.of());

        ContextProjectionResult result = projector.project(bundle, BUDGET);

        assertThat(result.context().blocks()).isEmpty();
        assertThat(result.projectedEvidenceCount()).isZero();
        assertThat(result.blocks()).isEmpty();
        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.policyVersion()).isEqualTo("context-policy-v1-current");
    }

    private AnswerContextCompactionPolicy halvingPolicy(ProjectionKind kind) {
        return new AnswerContextCompactionPolicy() {
            @Override
            public String version() {
                return "test-extractive-halving";
            }

            @Override
            public PolicyProjection project(EvidenceBundle evidence, AnswerContext baseline,
                                             AnswerContextBudget budget) {
                List<AnswerContextBlock> blocks = new ArrayList<>();
                List<ProjectionKind> kinds = new ArrayList<>();
                for (AnswerContextBlock block : baseline.blocks()) {
                    String content = block.content();
                    String compacted = content.substring(0, content.offsetByCodePoints(0,
                            Math.max(1, content.codePointCount(0, content.length()) / 2)));
                    blocks.add(new AnswerContextBlock(block.citationId(), block.evidenceKind(),
                            block.authorityIdentity(), compacted, true, block.contentHash(),
                            block.provenance()));
                    kinds.add(kind);
                }
                return new PolicyProjection(blocks, kinds);
            }
        };
    }

    private AnswerContextCompactionPolicy identityPolicy(ProjectionKind kind) {
        return new AnswerContextCompactionPolicy() {
            @Override
            public String version() {
                return "test-identity-kind";
            }

            @Override
            public PolicyProjection project(EvidenceBundle evidence, AnswerContext baseline,
                                             AnswerContextBudget budget) {
                return new PolicyProjection(baseline.blocks(),
                        baseline.blocks().stream().map(ignored -> kind).toList());
            }
        };
    }

    private AnswerContextCompactionPolicy shiftedIdentityPolicy(String identity) {
        return new AnswerContextCompactionPolicy() {
            @Override
            public String version() {
                return "test-drift";
            }

            @Override
            public PolicyProjection project(EvidenceBundle evidence, AnswerContext baseline,
                                             AnswerContextBudget budget) {
                List<AnswerContextBlock> blocks = new ArrayList<>();
                for (AnswerContextBlock block : baseline.blocks()) {
                    blocks.add(new AnswerContextBlock(block.citationId(), block.evidenceKind(),
                            identity, block.content(), block.contentTruncated(),
                            block.contentHash(), block.provenance()));
                }
                return new PolicyProjection(blocks, kindsFor(blocks));
            }
        };
    }

    private AnswerContextCompactionPolicy extraBlockPolicy() {
        return new AnswerContextCompactionPolicy() {
            @Override
            public String version() {
                return "test-extra";
            }

            @Override
            public PolicyProjection project(EvidenceBundle evidence, AnswerContext baseline,
                                             AnswerContextBudget budget) {
                List<AnswerContextBlock> blocks = new ArrayList<>(baseline.blocks());
                blocks.add(new AnswerContextBlock("E" + (blocks.size() + 1), EvidenceKind.WIKI,
                        "WIKI:resurrected", "resurrected content", false, "hash",
                        new AnswerContextProvenance.Wiki("Resurrected", "", null)));
                return new PolicyProjection(blocks, kindsFor(blocks));
            }
        };
    }

    private AnswerContextCompactionPolicy grownContentPolicy() {
        return new AnswerContextCompactionPolicy() {
            @Override
            public String version() {
                return "test-grown";
            }

            @Override
            public PolicyProjection project(EvidenceBundle evidence, AnswerContext baseline,
                                             AnswerContextBudget budget) {
                List<AnswerContextBlock> blocks = new ArrayList<>();
                for (AnswerContextBlock block : baseline.blocks()) {
                    blocks.add(new AnswerContextBlock(block.citationId(), block.evidenceKind(),
                            block.authorityIdentity(), block.content() + block.content(), true,
                            block.contentHash(), block.provenance()));
                }
                return new PolicyProjection(blocks, kindsFor(blocks));
            }
        };
    }

    private AnswerContextCompactionPolicy sameContentWithoutFlagsPolicy() {
        return new AnswerContextCompactionPolicy() {
            @Override
            public String version() {
                return "test-unflagged";
            }

            @Override
            public PolicyProjection project(EvidenceBundle evidence, AnswerContext baseline,
                                             AnswerContextBudget budget) {
                List<AnswerContextBlock> blocks = new ArrayList<>();
                for (AnswerContextBlock block : baseline.blocks()) {
                    blocks.add(new AnswerContextBlock(block.citationId(), block.evidenceKind(),
                            block.authorityIdentity(), block.content(), false,
                            block.contentHash(), block.provenance()));
                }
                return new PolicyProjection(blocks, kindsFor(blocks));
            }
        };
    }

    private AnswerContextCompactionPolicy blankVersionPolicy() {
        return new AnswerContextCompactionPolicy() {
            @Override
            public String version() {
                return " ";
            }

            @Override
            public PolicyProjection project(EvidenceBundle evidence, AnswerContext baseline,
                                             AnswerContextBudget budget) {
                return new PolicyProjection(baseline.blocks(),
                        kindsFor(baseline.blocks()));
            }
        };
    }

    private static List<ProjectionKind> kindsFor(List<AnswerContextBlock> blocks) {
        List<ProjectionKind> kinds = new ArrayList<>();
        for (AnswerContextBlock block : blocks) {
            kinds.add(block.contentTruncated() ? ProjectionKind.TRUNCATED : ProjectionKind.VERBATIM);
        }
        return kinds;
    }

    private static EvidenceBundle bundle(List<EvidenceItem> items) {
        int characters = items.stream().mapToInt(item ->
                item.content().codePointCount(0, item.content().length())).sum();
        return new EvidenceBundle("projection", RetrievalMode.HYBRID_FTS, WORKSPACE, items,
                new EvidenceBudget(8, 100_000, items.size(), characters, (characters + 3) / 4,
                        false), items.size(), 0, items.isEmpty(),
                RetrievalDiagnostics.lexical());
    }

    private static EvidenceItem wiki(String id, String title, String path, String content) {
        return new EvidenceItem(EvidenceKind.WIKI, id, WORKSPACE, 0.9, content, "snippet", false,
                "hash-" + id, id, title, "CONCEPT", path, 1,
                null, null, null, null, null, null, null);
    }

    private static EvidenceItem source(long chunkId, long documentId, String documentName,
                                       String content) {
        return new EvidenceItem(EvidenceKind.SOURCE_CHUNK, Long.toString(chunkId), WORKSPACE, 0.8,
                content, "snippet", false, "hash-source-" + chunkId, null, null, null, null,
                null, chunkId, documentId, documentName, 1, 1, "Overview", "Root > Overview");
    }
}
