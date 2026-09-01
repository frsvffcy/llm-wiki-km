package org.km.llmwiki.ai.answer;

/** Versioned, provider-neutral prompt produced by the application-owned grounded contract. */
public record GroundedAnswerPrompt(String identifier, String version, String contentHash,
                                   String renderedPrompt) {
    public GroundedAnswerPrompt {
        if (identifier == null || identifier.isBlank()
                || version == null || version.isBlank()
                || contentHash == null || contentHash.isBlank()
                || renderedPrompt == null || renderedPrompt.isBlank()) {
            throw new IllegalArgumentException("grounded answer prompt fields are required");
        }
    }

    /** Alias matching the terminology used by the document-analysis prompt contract. */
    public String content() {
        return renderedPrompt;
    }
}
