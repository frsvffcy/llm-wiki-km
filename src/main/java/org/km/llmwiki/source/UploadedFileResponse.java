package org.km.llmwiki.source;

public record UploadedFileResponse(
        Long documentId,
        String fileName,
        String status,
        boolean duplicate) {
}
