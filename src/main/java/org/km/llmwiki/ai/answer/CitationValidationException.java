package org.km.llmwiki.ai.answer;

/** Explicit rejection of malformed or hallucinated application citation IDs. */
public final class CitationValidationException extends IllegalArgumentException {
    public CitationValidationException(String message) {
        super(message);
    }
}
