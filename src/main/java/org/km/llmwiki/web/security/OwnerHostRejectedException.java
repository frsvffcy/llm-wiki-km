package org.km.llmwiki.web.security;

/** Request Host failed the allowlist: malformed, missing, or not allowlisted. */
public class OwnerHostRejectedException extends RuntimeException {
    public OwnerHostRejectedException(String message) {
        super(message);
    }
}
