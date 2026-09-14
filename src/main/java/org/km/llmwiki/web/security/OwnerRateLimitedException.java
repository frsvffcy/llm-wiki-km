package org.km.llmwiki.web.security;

/** Client exceeded the bounded request-rate policy for a high-risk surface. */
public class OwnerRateLimitedException extends RuntimeException {
    public OwnerRateLimitedException(String message) {
        super(message);
    }
}
