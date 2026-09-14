package org.km.llmwiki.ai.query;

/** Bounded provider failure classification used by deterministic original-query fallback. */
public final class QueryRewriteException extends RuntimeException {

    public enum Type {
        UNAVAILABLE,
        INVALID_RESPONSE
    }

    private final Type type;

    public QueryRewriteException(Type type, String message) {
        super(message);
        this.type = type;
    }

    public Type type() {
        return type;
    }
}
