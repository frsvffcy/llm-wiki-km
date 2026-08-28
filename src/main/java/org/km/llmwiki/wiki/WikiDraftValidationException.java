package org.km.llmwiki.wiki;

/** Raised when an approved proposal cannot safely satisfy the Wiki Draft contract. */
public class WikiDraftValidationException extends IllegalArgumentException {

    public enum Reason {
        PROPOSAL_NOT_APPROVED,
        UNSUPPORTED_ACTION,
        AMBIGUOUS_CANDIDATE_MAPPING,
        UNSUPPORTED_CANDIDATE_MAPPING,
        INVALID_NORMALIZED_DATA,
        INVALID_EVIDENCE,
        UNSAFE_TARGET_REFERENCE,
        PATH_CONTRACT_MISMATCH
    }

    private final Reason reason;

    public WikiDraftValidationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public WikiDraftValidationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
