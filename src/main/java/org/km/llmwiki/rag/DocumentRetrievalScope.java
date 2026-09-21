package org.km.llmwiki.rag;

/** Application-owned identity that constrains one retrieval to a single source document. */
public record DocumentRetrievalScope(long documentId) {

    public DocumentRetrievalScope {
        if (documentId <= 0) {
            throw new IllegalArgumentException("documentId must be positive");
        }
    }
}
