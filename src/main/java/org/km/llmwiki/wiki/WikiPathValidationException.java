package org.km.llmwiki.wiki;

/**
 * Thrown when a Wiki page path or page type fails validation.
 *
 * <p>Each instance carries a {@link Reason} that precisely identifies
 * the violation, allowing callers to map the error to an appropriate
 * HTTP error code and user-facing message without pattern-matching strings.
 */
public class WikiPathValidationException extends RuntimeException {

    public enum Reason {
        /** The page type string is null, blank, or not in the controlled vocabulary. */
        UNKNOWN_PAGE_TYPE,

        /** The title cannot be normalized to a safe, non-empty filename. */
        INVALID_TITLE,

        /** The path contains a {@code ..} segment that could escape the vault boundary. */
        PATH_TRAVERSAL_DETECTED,

        /** The supplied path is an absolute filesystem path instead of a relative path. */
        ABSOLUTE_PATH_NOT_ALLOWED,

        /** After real-path resolution, the path points outside the workspace vault directory. */
        OUTSIDE_VAULT_BOUNDARY,

        /** A symlink in the path resolves to a target outside the workspace vault directory. */
        SYMLINK_ESCAPE
    }

    private final Reason reason;

    public WikiPathValidationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public WikiPathValidationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /** Returns the precise reason for the validation failure. */
    public Reason reason() {
        return reason;
    }
}
