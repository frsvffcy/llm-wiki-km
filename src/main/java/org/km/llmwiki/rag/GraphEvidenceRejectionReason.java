package org.km.llmwiki.rag;

/**
 * Typed reason a single graph candidate was not admitted as canonical evidence. Expected
 * rejections fail closed for one candidate; they never mask batch-level graph unavailability.
 */
public enum GraphEvidenceRejectionReason {
    /** Entity type itself is not a canonical evidence authority (e.g. tag-like navigation node). */
    NON_EVIDENCE_ENTITY,
    /** Entity resolves to current canonical authority but has no chunk-level citation identity. */
    NON_CITATION_AUTHORITY,
    /** Candidate identity or provenance points outside the requesting workspace. */
    WORKSPACE_MISMATCH,
    /** Provenance, stable id, or metadata is incomplete or internally inconsistent. */
    MALFORMED_PROVENANCE,
    /** Canonical authority no longer exists for the candidate. */
    AUTHORITY_MISSING,
    /** Canonical authority exists but its content/revision/hash drifted after projection build. */
    AUTHORITY_STALE,
    /** Canonical authority exists but is no longer eligible (unpublished/superseded/deleted). */
    AUTHORITY_INELIGIBLE,
    /** Traversal path contains a relation type outside the admitted projection profile. */
    DISALLOWED_RELATION,
    /** Path or entity provenance was built for a different projection version/snapshot. */
    STALE_PROVENANCE,
    /** Same canonical evidence identity was already admitted in this batch. */
    DUPLICATE_IDENTITY
}
