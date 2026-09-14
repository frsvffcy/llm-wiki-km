package org.km.llmwiki.source;

public record ExtractionResponse(long documentId, String parseStatus, int chunkCount,
                                 String normalizationPolicyVersion,
                                 String errorCode, String errorMessage) {
}
