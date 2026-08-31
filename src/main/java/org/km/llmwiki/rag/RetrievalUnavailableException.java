package org.km.llmwiki.rag;

/** Retrieval did not complete because a required search or authority dependency was unavailable. */
public class RetrievalUnavailableException extends RuntimeException {

    public enum Dependency {
        WORKSPACE_AUTHORITY,
        SEARCH_INDEX,
        WIKI_AUTHORITY,
        SOURCE_AUTHORITY
    }

    private final Dependency dependency;

    public RetrievalUnavailableException(Dependency dependency, Throwable cause) {
        super("Retrieval dependency is unavailable: " + dependency.name(), cause);
        this.dependency = dependency;
    }

    public Dependency dependency() {
        return dependency;
    }
}
