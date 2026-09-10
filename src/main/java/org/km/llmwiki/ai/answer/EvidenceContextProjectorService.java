package org.km.llmwiki.ai.answer;

import org.km.llmwiki.rag.EvidenceBundle;
import org.km.llmwiki.rag.EvidenceItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Application-owned implementation of the single production context-packing path: assemble the
 * bounded baseline with {@link AnswerContextAssembler} (canonical identity, citation ids,
 * provenance, and content hash stay authoritative), then apply the active versioned
 * {@link AnswerContextCompactionPolicy}.
 *
 * <p>Every policy output is revalidated against hard invariants (same block count and order,
 * identical citation/identity/kind/hash/provenance, no blank content, no code-point growth) so
 * a defective policy can never drift citations, resurrect rejected evidence, or exceed the
 * bounded budget. Any typed or unexpected policy failure falls back deterministically to the
 * bounded baseline with a recorded failure type — projection failures are never silently
 * swallowed and never fall back to an unbounded context.
 */
@Service
public final class EvidenceContextProjectorService implements EvidenceContextProjector {

    private static final Logger LOG = LoggerFactory.getLogger(EvidenceContextProjectorService.class);

    private final AnswerContextAssembler assembler;
    private final AnswerContextCompactionPolicyRegistry registry;

    public EvidenceContextProjectorService(AnswerContextAssembler assembler,
                                           AnswerContextCompactionPolicyRegistry registry) {
        this.assembler = assembler;
        this.registry = registry;
    }

    @Override
    public ContextProjectionResult project(EvidenceBundle evidence, AnswerContextBudget budget) {
        return project(evidence, budget, activePolicy());
    }

    @Override
    public ContextProjectionResult project(EvidenceBundle evidence, AnswerContextBudget budget,
                                           AnswerContextCompactionPolicy policy) {
        requirePolicy(policy);
        if (evidence == null) {
            throw new IllegalArgumentException("evidence bundle must not be null");
        }
        if (budget == null) {
            throw new IllegalArgumentException("context budget must not be null");
        }
        try {
            requireVersion(policy);
            return projectWithPolicy(evidence, budget, policy);
        } catch (AnswerContextProjectionException failure) {
            LOG.warn("context projection failed; falling back to the bounded baseline: {}",
                    failure.getMessage());
            return baselineFallback(evidence, budget, safeVersion(policy), failure.failureType());
        } catch (RuntimeException failure) {
            LOG.warn("context projection failed unexpectedly; falling back to the bounded baseline",
                    failure);
            return baselineFallback(evidence, budget, safeVersion(policy),
                    ContextProjectionFailureType.INVALID_POLICY);
        }
    }

    private static void requireVersion(AnswerContextCompactionPolicy policy) {
        if (policy.version() == null || policy.version().isBlank()) {
            throw new AnswerContextProjectionException(ContextProjectionFailureType.INVALID_POLICY,
                    "context compaction policy version must not be blank");
        }
    }

    private static String safeVersion(AnswerContextCompactionPolicy policy) {
        try {
            String version = policy.version();
            return version == null || version.isBlank() ? "unknown" : version;
        } catch (RuntimeException failure) {
            return "unknown";
        }
    }

    private AnswerContextCompactionPolicy activePolicy() {
        AnswerContextCompactionPolicy policy = registry.active();
        if (policy == null) {
            throw new AnswerContextProjectionException(ContextProjectionFailureType.INVALID_POLICY,
                    "no active context compaction policy");
        }
        return policy;
    }

    private static void requirePolicy(AnswerContextCompactionPolicy policy) {
        if (policy == null) {
            throw new AnswerContextProjectionException(ContextProjectionFailureType.INVALID_POLICY,
                    "context compaction policy must not be null");
        }
    }

    private ContextProjectionResult projectWithPolicy(EvidenceBundle evidence,
                                                      AnswerContextBudget budget,
                                                      AnswerContextCompactionPolicy policy) {
        AnswerContext baseline = assembler.assemble(evidence, budget);
        if (baseline.blocks().isEmpty()) {
            return emptyResult(evidence, policy, baseline);
        }

        AnswerContextCompactionPolicy.PolicyProjection projection = policy.project(evidence,
                baseline, budget);
        if (projection == null) {
            throw new AnswerContextProjectionException(ContextProjectionFailureType.INVALID_POLICY,
                    "context compaction policy returned no projection");
        }
        validateAgainstBaseline(policy, baseline, projection);

        int projectedCodePoints = blockCodePoints(projection.blocks());
        int baselineCodePoints = blockCodePoints(baseline.blocks());
        int originalCodePoints = evidenceCodePoints(evidence.items());
        double reductionRatio = baselineCodePoints == 0 ? 0.0d
                : 1.0d - (double) projectedCodePoints / baselineCodePoints;

        return new ContextProjectionResult(policy.version(),
                new AnswerContext(List.copyOf(projection.blocks()),
                        new AnswerContextUsage(projection.blocks().size(), projectedCodePoints,
                                baseline.usage().truncated()
                                        || projection.blocks().stream().anyMatch(
                                        AnswerContextBlock::contentTruncated))),
                evidence.items().size(), projection.blocks().size(), originalCodePoints,
                baselineCodePoints, projectedCodePoints, baseline.usage().truncated(),
                reductionRatio,
                projectedBlocks(baseline, projection), false, null);
    }

    private static void validateAgainstBaseline(AnswerContextCompactionPolicy policy,
                                                AnswerContext baseline,
                                                AnswerContextCompactionPolicy.PolicyProjection projection) {
        List<AnswerContextBlock> baselineBlocks = baseline.blocks();
        List<AnswerContextBlock> projected = projection.blocks();
        List<ProjectionKind> kinds = projection.kinds();
        if (projected.size() != baselineBlocks.size()) {
            throw new AnswerContextProjectionException(
                    ContextProjectionFailureType.PROJECTION_INVARIANT_VIOLATION,
                    "projection must not add or remove evidence blocks");
        }
        for (int index = 0; index < projected.size(); index++) {
            AnswerContextBlock original = baselineBlocks.get(index);
            AnswerContextBlock block = projected.get(index);
            if (block == null) {
                throw new AnswerContextProjectionException(
                        ContextProjectionFailureType.PROJECTION_INVARIANT_VIOLATION,
                        "projection must not contain null blocks");
            }
            if (!block.citationId().equals(original.citationId())
                    || !block.authorityIdentity().equals(original.authorityIdentity())
                    || block.evidenceKind() != original.evidenceKind()
                    || !block.contentHash().equals(original.contentHash())
                    || !block.provenance().equals(original.provenance())) {
                throw new AnswerContextProjectionException(
                        ContextProjectionFailureType.PROJECTION_INVARIANT_VIOLATION,
                        "projection must not change evidence identity, citation id, or provenance");
            }
            int baselineCodePoints = original.content().codePointCount(0, original.content().length());
            int projectedCodePoints = block.content().codePointCount(0, block.content().length());
            if (block.content().isBlank()) {
                throw new AnswerContextProjectionException(
                        ContextProjectionFailureType.PROJECTION_INVARIANT_VIOLATION,
                        "projection must not remove all content from a cited block");
            }
            if (projectedCodePoints > baselineCodePoints) {
                throw new AnswerContextProjectionException(
                        ContextProjectionFailureType.PROJECTION_LIMIT_EXCEEDED,
                        "projection must not exceed the bounded baseline block");
            }
            boolean contentCompacted = projectedCodePoints < baselineCodePoints;
            if (block.contentTruncated() != (contentCompacted || original.contentTruncated())) {
                throw new AnswerContextProjectionException(
                        ContextProjectionFailureType.PROJECTION_INVARIANT_VIOLATION,
                        "projection truncation flags must honestly report compaction");
            }
            if (kinds.get(index) == null) {
                throw new AnswerContextProjectionException(
                        ContextProjectionFailureType.INVALID_POLICY,
                        "projection kind must not be null");
            }
            ProjectionKind projectionKind = kinds.get(index);
            boolean kindMatchesContent;
            if (contentCompacted) {
                kindMatchesContent = projectionKind == ProjectionKind.EXTRACTIVE
                        || projectionKind == ProjectionKind.TRUNCATED;
            } else if (original.contentTruncated()) {
                // A baseline that was already truncated must remain explicitly labelled as
                // truncation; an unchanged block cannot be relabelled as extractive compaction.
                kindMatchesContent = projectionKind == ProjectionKind.TRUNCATED;
            } else {
                kindMatchesContent = projectionKind == ProjectionKind.VERBATIM
                        || projectionKind == ProjectionKind.NO_OP;
            }
            if (!kindMatchesContent) {
                throw new AnswerContextProjectionException(
                        ContextProjectionFailureType.PROJECTION_INVARIANT_VIOLATION,
                        "projection kind must honestly match the actual compaction");
            }
        }
    }

    private ContextProjectionResult baselineFallback(EvidenceBundle evidence,
                                                     AnswerContextBudget budget,
                                                     String policyVersion,
                                                     ContextProjectionFailureType failureType) {
        AnswerContext baseline = assembler.assemble(evidence, budget);
        List<AnswerContextBlock> blocks = baseline.blocks();
        int baselineCodePoints = blockCodePoints(blocks);
        return new ContextProjectionResult(policyVersion, baseline, evidence.items().size(),
                blocks.size(), evidenceCodePoints(evidence.items()), baselineCodePoints,
                baselineCodePoints, baseline.usage().truncated(), 0.0d,
                baselineProjectedBlocks(blocks), true, failureType);
    }

    private ContextProjectionResult emptyResult(EvidenceBundle evidence,
                                                AnswerContextCompactionPolicy policy,
                                                AnswerContext baseline) {
        return new ContextProjectionResult(policy.version(), baseline, evidence.items().size(),
                0, evidenceCodePoints(evidence.items()), 0, 0, baseline.usage().truncated(),
                0.0d, List.of(), false, null);
    }

    private static List<ProjectedEvidenceBlock> projectedBlocks(AnswerContext baseline,
                                                                AnswerContextCompactionPolicy.PolicyProjection projection) {
        List<ProjectedEvidenceBlock> blocks = new ArrayList<>(projection.blocks().size());
        for (int index = 0; index < projection.blocks().size(); index++) {
            AnswerContextBlock original = baseline.blocks().get(index);
            AnswerContextBlock projected = projection.blocks().get(index);
            int originalCodePoints = original.content().codePointCount(0, original.content().length());
            int projectedCodePoints = projected.content().codePointCount(0,
                    projected.content().length());
            blocks.add(new ProjectedEvidenceBlock(projected.citationId(),
                    projected.authorityIdentity(), projection.kinds().get(index),
                    originalCodePoints, projectedCodePoints,
                    projectedCodePoints < originalCodePoints));
        }
        return List.copyOf(blocks);
    }

    private static List<ProjectedEvidenceBlock> baselineProjectedBlocks(List<AnswerContextBlock> blocks) {
        List<ProjectedEvidenceBlock> projected = new ArrayList<>(blocks.size());
        for (AnswerContextBlock block : blocks) {
            int codePoints = block.content().codePointCount(0, block.content().length());
            projected.add(new ProjectedEvidenceBlock(block.citationId(), block.authorityIdentity(),
                    block.contentTruncated() ? ProjectionKind.TRUNCATED : ProjectionKind.VERBATIM,
                    codePoints, codePoints, false));
        }
        return List.copyOf(projected);
    }

    private static int blockCodePoints(List<AnswerContextBlock> blocks) {
        return blocks.stream().mapToInt(block -> block.content().codePointCount(0,
                block.content().length())).sum();
    }

    private static int evidenceCodePoints(List<EvidenceItem> items) {
        return items.stream().mapToInt(item -> item.content().codePointCount(0,
                item.content().length())).sum();
    }
}
