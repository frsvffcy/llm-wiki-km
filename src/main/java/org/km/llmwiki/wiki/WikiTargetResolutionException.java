package org.km.llmwiki.wiki;

/** Fail-closed outcome when a Wiki target cannot be resolved unambiguously and safely. */
public class WikiTargetResolutionException extends RuntimeException {

    public enum Reason {
        INVALID_TARGET_REFERENCE,
        CREATE_TARGET_EXISTS,
        TARGET_NOT_FOUND,
        TARGET_AMBIGUOUS,
        CROSS_WORKSPACE_AMBIGUITY,
        TARGET_NOT_VISIBLE,
        TARGET_PAGE_TYPE_MISMATCH,
        CANONICAL_INVARIANT_VIOLATION
    }

    private final Reason reason;

    public WikiTargetResolutionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
