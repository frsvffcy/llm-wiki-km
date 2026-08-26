package org.km.llmwiki.source;

public record InboxDocumentRow(
        Long documentId,
        String fileName,
        String originalFileName,
        String extension,
        String mimeType,
        Long fileSize,
        String status,
        String createdAt) {
}
