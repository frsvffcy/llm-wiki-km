package org.km.llmwiki.source;

public record InboxDocumentRow(
        Long documentId,
        String fileName,
        String originalFileName,
        String extension,
        String mimeType,
        Long fileSize,
        String status,
        String parseStatus,
        String errorCode,
        String errorMessage,
        String createdAt,
        DocumentUsabilityReadiness usability) {

    public InboxDocumentRow(Long documentId,
                            String fileName,
                            String originalFileName,
                            String extension,
                            String mimeType,
                            Long fileSize,
                            String status,
                            String parseStatus,
                            String errorCode,
                            String errorMessage,
                            String createdAt) {
        this(documentId, fileName, originalFileName, extension, mimeType, fileSize,
                status, parseStatus, errorCode, errorMessage, createdAt, null);
    }

    public InboxDocumentRow withUsability(DocumentUsabilityReadiness readiness) {
        return new InboxDocumentRow(documentId, fileName, originalFileName, extension, mimeType,
                fileSize, status, parseStatus, errorCode, errorMessage, createdAt, readiness);
    }
}
