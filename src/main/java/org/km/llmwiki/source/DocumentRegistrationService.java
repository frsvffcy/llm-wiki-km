package org.km.llmwiki.source;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

@Service
public class DocumentRegistrationService {

    private final DocumentRepository documentRepository;

    public DocumentRegistrationService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Transactional
    public RegistrationResult replaceVersion(long workspaceId, DocumentSummary superseded,
                                             String originalFileName, String storedFileName,
                                             String sourcePath, String sha256, Long fileSize, String mimeType) {
        documentRepository.markSuperseded(superseded.id());
        return doRegister(workspaceId, originalFileName, storedFileName, sourcePath,
                sha256, fileSize, mimeType, superseded.id());
    }

    public RegistrationResult register(long workspaceId, String originalFileName, String storedFileName,
                                       String sourcePath, String sha256, Long fileSize, String mimeType,
                                       Long parentVersionDocumentId) {
        return doRegister(workspaceId, originalFileName, storedFileName, sourcePath,
                sha256, fileSize, mimeType, parentVersionDocumentId);
    }

    private RegistrationResult doRegister(long workspaceId, String originalFileName, String storedFileName,
                                          String sourcePath, String sha256, Long fileSize, String mimeType,
                                          Long parentVersionDocumentId) {
        Optional<DocumentSummary> original = documentRepository.findActiveByWorkspaceAndSha256(workspaceId, sha256);
        String status = original.isPresent()
                ? DocumentStatus.DUPLICATE.name()
                : DocumentStatus.PENDING.name();
        Long duplicateOf = original.map(DocumentSummary::id).orElse(null);

        long id = documentRepository.insert(workspaceId, storedFileName, originalFileName,
                normalizeExtension(storedFileName), sourcePath, sha256,
                fileSize, mimeType, DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                status, duplicateOf, parentVersionDocumentId);
        return new RegistrationResult(id, status, duplicateOf);
    }

    public static String normalizeExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record RegistrationResult(long documentId, String status, Long duplicateOfDocumentId) {
    }
}
