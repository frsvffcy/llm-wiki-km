package org.km.llmwiki.source;

/** A processed document that is eligible to enter the LLM analysis pipeline. */
public record DocumentAnalysisTarget(long documentId, String originalFileName, String mimeType,
                                     String extractedTextHash) {
}
