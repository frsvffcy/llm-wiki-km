package org.km.llmwiki.web.security;

/** Unauthenticated owner request: no, invalid, expired, or revoked session. */
public class OwnerAuthenticationException extends RuntimeException {
    public OwnerAuthenticationException(String message) {
        super(message);
    }
}
