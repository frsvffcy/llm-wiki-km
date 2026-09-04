package org.km.llmwiki.wiki;

/** Raised when the #90 target snapshot cannot be captured as a safe, reviewable baseline. */
public class WikiDraftTargetException extends RuntimeException {

    public enum Reason {
        CREATE_TARGET_EXISTS,
        TARGET_FILE_MISSING,
        TARGET_NOT_REGULAR_FILE,
        TARGET_CONTENT_INVALID,
        TARGET_CONTENT_HASH_MISMATCH
    }

    private final Reason reason;

    public WikiDraftTargetException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public WikiDraftTargetException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
