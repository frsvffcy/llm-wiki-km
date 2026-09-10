package org.km.llmwiki.ai.answer;

import org.km.llmwiki.rag.EvidenceBundle;

/**
 * Provider-neutral boundary between the already-retrieved {@link EvidenceBundle} and the
 * ephemeral provider context. Evidence authority is separate from context representation:
 * retrieval and authority revalidation own the bundle, this boundary owns the one production
 * context-packing path, and citation validation keeps validating against canonical evidence
 * identities. A projection never mutates the bundle, never creates canonical identity, and
 * never becomes durable knowledge.
 */
public interface EvidenceContextProjector {

    /** Projects with the active versioned policy resolved by the policy registry. */
    ContextProjectionResult project(EvidenceBundle evidence, AnswerContextBudget budget);

    /**
     * Projects with an explicit policy. The production Ask path resolves the policy through
     * the registry; the explicit overload exists for deterministic evaluation and tests.
     */
    ContextProjectionResult project(EvidenceBundle evidence, AnswerContextBudget budget,
                                    AnswerContextCompactionPolicy policy);
}
