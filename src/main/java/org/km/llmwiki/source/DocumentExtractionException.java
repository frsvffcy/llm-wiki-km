package org.km.llmwiki.source;

public class DocumentExtractionException extends RuntimeException {

    private final String errorCode;

    public DocumentExtractionException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
