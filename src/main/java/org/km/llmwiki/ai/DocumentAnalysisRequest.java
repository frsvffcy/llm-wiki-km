package org.km.llmwiki.ai;

import java.util.List;
import java.util.Objects;

/** Input sent to an LLM provider; it deliberately contains no provider credentials. */
public record DocumentAnalysisRequest(DocumentAnalysisMetadata document,
                                      List<SourceChunkEvidence> sourceChunkEvidence,
                                      DocumentAnalysisPrompt prompt,
                                      AnalysisSettings settings) {

    public DocumentAnalysisRequest(DocumentAnalysisMetadata document,
                                   List<SourceChunkEvidence> sourceChunkEvidence) {
        this(document, sourceChunkEvidence, null, null);
    }

    public DocumentAnalysisRequest {
        document = Objects.requireNonNull(document, "document must not be null");
        sourceChunkEvidence = List.copyOf(Objects.requireNonNull(sourceChunkEvidence,
                "sourceChunkEvidence must not be null"));
        if (sourceChunkEvidence.isEmpty()) {
            throw new IllegalArgumentException("sourceChunkEvidence must not be empty");
        }
        if ((prompt == null) != (settings == null)) {
            throw new IllegalArgumentException("prompt and settings must be supplied together");
        }
    }

    /** Returns the provider-ready request after workspace prompt and settings have been resolved. */
    public DocumentAnalysisRequest withConfiguration(LoadedDocumentAnalysisConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        return new DocumentAnalysisRequest(document, sourceChunkEvidence, configuration.prompt(),
                configuration.settings());
    }
}
