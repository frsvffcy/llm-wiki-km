package org.km.llmwiki.ai.answer;

/**
 * Application-owned typed projection semantics for one evidence block. Projection never
 * changes evidence identity: every projected block still resolves to the same canonical
 * {@code AnswerContextBlock} identity, citation id, and content hash as the baseline.
 */
public enum ProjectionKind {
    /** Block content equals the baseline content byte-for-byte (nothing was compacted). */
    VERBATIM,
    /** Deterministic extractive compaction (for example sentence/heading skeletons). */
    EXTRACTIVE,
    /** Deterministic truncation of the evidence content at an application-owned bound. */
    TRUNCATED,
    /** The policy decided not to compact this content; it is carried as-is within budget. */
    NO_OP
}
