package org.km.llmwiki.ai;

import java.util.List;
import java.util.Objects;

/** Input sent to an LLM provider; it deliberately contains no provider credentials. */
public record DocumentAnalysisRequest(DocumentAnalysisMetadata document,
                                      List<SourceChunkEvidence> sourceChunkEvidence) {

    public DocumentAnalysisRequest {
        document = Objects.requireNonNull(document, "document must not be null");
        sourceChunkEvidence = List.copyOf(Objects.requireNonNull(sourceChunkEvidence,
                "sourceChunkEvidence must not be null"));
        if (sourceChunkEvidence.isEmpty()) {
            throw new IllegalArgumentException("sourceChunkEvidence must not be empty");
        }
    }
}
