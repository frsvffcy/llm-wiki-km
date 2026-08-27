package org.km.llmwiki.ai;

/** Indicates that a document analysis request must stop before it reaches an LLM provider. */
public final class PromptLoadException extends RuntimeException {

    private final PromptLoadErrorCode errorCode;

    public PromptLoadException(PromptLoadErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PromptLoadException(PromptLoadErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public PromptLoadErrorCode errorCode() {
        return errorCode;
    }
}
