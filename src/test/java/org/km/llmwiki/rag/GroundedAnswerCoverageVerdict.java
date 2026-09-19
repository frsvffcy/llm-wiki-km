package org.km.llmwiki.rag;

/**
 * Test-only typed verdict for grounded-answer citation coverage (#551).
 *
 * <p>This is benchmark truth, not a new runtime validation for
 * {@code GroundedAnswerResponseContract}. {@code ANSWERED} only guarantees at least one
 * application-known citation; whether that citation set covers the benchmark required set is
 * evaluated here.
 */
enum GroundedAnswerCoverageVerdict {
    /** Every required canonical identity is cited. */
    COMPLETE,
    /** At least evaluated, but at least one required identity is missing. */
    PARTIAL,
    /** Provider reported {@code insufficientEvidence=true} with no citations. */
    ABSTAINED,
    /** Observed citations violate the runtime contract (empty or unknown). */
    INVALID,
    /** Evaluation could not observe the required inputs; must not be read as a quality score. */
    UNOBSERVED
}
