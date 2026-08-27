package org.km.llmwiki.source;

public record ExtractionResponse(long documentId, String parseStatus, int chunkCount) {
}
