package org.km.llmwiki.ai;

/** The non-secret configuration prepared for one document analysis invocation. */
public record LoadedDocumentAnalysisConfiguration(DocumentAnalysisPrompt prompt,
                                                  AnalysisSettings settings) {
}
