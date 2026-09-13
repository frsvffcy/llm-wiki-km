package org.km.llmwiki.wiki;

/** The requested Published Wiki page does not exist in the current workspace. */
public class WikiPageNotFoundException extends RuntimeException {

    public WikiPageNotFoundException(String knowledgeId) {
        super("Published Wiki page not found: " + knowledgeId);
    }
}
