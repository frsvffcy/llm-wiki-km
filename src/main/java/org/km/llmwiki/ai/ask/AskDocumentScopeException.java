package org.km.llmwiki.ai.ask;

import java.util.Objects;

/** Typed fail-closed signal when a document-scoped Ask can no longer trust its scope. */
public final class AskDocumentScopeException extends RuntimeException {

    public enum Reason {
        INVALID,
        STALE
    }

    private final Reason reason;

    public AskDocumentScopeException(Reason reason) {
        super("Ask document scope is " + Objects.requireNonNull(reason, "reason is required"));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
