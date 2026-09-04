package org.km.llmwiki.wiki;

/** Published Wiki authority could not be read because its infrastructure is unavailable. */
public class PublishedWikiUnavailableException extends RuntimeException {

    public PublishedWikiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
