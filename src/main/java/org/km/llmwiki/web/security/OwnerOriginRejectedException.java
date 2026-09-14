package org.km.llmwiki.web.security;

/**
 * Request Origin failed the allowlist, or a cookie-authenticated mutation
 * arrived without the Origin equivalent the cookie contract requires.
 */
public class OwnerOriginRejectedException extends RuntimeException {
    public OwnerOriginRejectedException(String message) {
        super(message);
    }
}
