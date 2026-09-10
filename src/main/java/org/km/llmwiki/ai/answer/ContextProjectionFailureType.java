package org.km.llmwiki.ai.answer;

/**
 * Typed failure taxonomy for context projection. Failures are never silently swallowed: the
 * projector falls back to the deterministic, bounded baseline and records the failure type.
 */
public enum ContextProjectionFailureType {
    /** The selected policy is null, unnamed, or returned an unusable projection shape. */
    INVALID_POLICY,
    /** A projected block broke an identity, citation, or budget invariant. */
    PROJECTION_INVARIANT_VIOLATION,
    /** A projected block would exceed the bounded context budget. */
    PROJECTION_LIMIT_EXCEEDED,
    /** The policy cannot project this content kind deterministically. */
    UNSUPPORTED_CONTENT_KIND
}
