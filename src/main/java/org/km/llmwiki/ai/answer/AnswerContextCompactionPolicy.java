package org.km.llmwiki.ai.answer;

import org.km.llmwiki.rag.EvidenceBundle;

import java.util.List;

/**
 * Versioned, immutable, deterministic Answer Context Compaction policy. A policy projects the
 * bounded baseline context into the final ephemeral provider context without ever changing
 * evidence identity, citation ids, provenance, or the canonical content hash, and without
 * ever exceeding the baseline's bounded code points.
 *
 * <p>Same evidence + same budget + same policy version must produce the same projection.
 * Policies are deterministic: no wall clock, randomness, provider responses, or LLM calls.
 * The complete {@link EvidenceBundle} is never mutated and remains the grounded authority.
 */
public interface AnswerContextCompactionPolicy {

    /** Stable policy version; changing behavior requires a new version, never a silent mutation. */
    String version();

    /**
     * Projects the baseline context. The returned blocks must be a same-sized, order-preserving
     * projection of the baseline blocks with identical identity, citation, provenance, and
     * content-hash fields; only content may be compacted within the baseline block bounds.
     */
    PolicyProjection project(EvidenceBundle evidence, AnswerContext baseline,
                             AnswerContextBudget budget);

    /** Per-block projection output aligned with the projected block list. */
    record PolicyProjection(List<AnswerContextBlock> blocks, List<ProjectionKind> kinds) {
        public PolicyProjection {
            if (blocks == null || kinds == null || blocks.size() != kinds.size()) {
                throw new IllegalArgumentException("policy projection blocks and kinds must align");
            }
            blocks = List.copyOf(blocks);
            kinds = List.copyOf(kinds);
        }
    }
}
