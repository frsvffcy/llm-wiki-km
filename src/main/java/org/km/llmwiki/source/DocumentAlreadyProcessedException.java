package org.km.llmwiki.source;

public class DocumentAlreadyProcessedException extends RuntimeException {

    public DocumentAlreadyProcessedException(long documentId, String status) {
        super("Document " + documentId + " has already been processed (status: " + status + ")");
    }
}
