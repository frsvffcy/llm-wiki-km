package org.km.llmwiki.source;

record DocumentExtractionTarget(long documentId, String fileName, String mimeType,
                                String sourcePath, String parseStatus) {
}
