package org.km.llmwiki.source;

public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(long documentId) {
        super("Document not found: " + documentId);
    }
}
