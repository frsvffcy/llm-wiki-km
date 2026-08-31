package org.km.llmwiki.wiki;

/** Canonical Published Wiki metadata or bytes failed fail-closed validation. */
public class PublishedWikiValidationException extends RuntimeException {

    public PublishedWikiValidationException(String message) {
        super(message);
    }

    public PublishedWikiValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
