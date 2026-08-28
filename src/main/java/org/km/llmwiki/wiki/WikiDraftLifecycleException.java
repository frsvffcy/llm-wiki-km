package org.km.llmwiki.wiki;

/** Raised when a caller requests an illegal persisted Draft lifecycle transition. */
public class WikiDraftLifecycleException extends RuntimeException {

    public WikiDraftLifecycleException(String message) {
        super(message);
    }
}
