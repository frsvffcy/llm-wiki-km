package org.km.llmwiki.wiki;

public class WikiPublishException extends RuntimeException {

    public enum Reason {
        DRAFT_NOT_READY,
        ACTION_NOT_CREATE,
        PROPOSAL_INVALID,
        TARGET_CONFLICT,
        OPERATION_CONFLICT,
        FILESYSTEM_FAILURE,
        CONTENT_VALIDATION_FAILED,
        METADATA_FAILURE,
        PUBLISHED_FILE_DRIFT,
        RECONCILIATION_REQUIRED
    }

    private final Reason reason;

    public WikiPublishException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public WikiPublishException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
