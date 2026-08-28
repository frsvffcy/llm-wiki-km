package org.km.llmwiki.wiki;

/** Workspace-scoped not-found result that does not disclose foreign Draft ids. */
public class WikiDraftNotFoundException extends RuntimeException {

    public WikiDraftNotFoundException(long draftId) {
        super("Wiki Draft not found: " + draftId);
    }
}
