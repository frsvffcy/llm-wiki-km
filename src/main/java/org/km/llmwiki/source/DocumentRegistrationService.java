package org.km.llmwiki.source;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
public class DocumentRegistrationService {

    private final DocumentRepository documentRepository;

    public DocumentRegistrationService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    public RegistrationResult register(long workspaceId, String fileName, String sourcePath,
                                       String sha256, Long fileSize, String mimeType,
                                       Long parentVersionDocumentId) {
        Optional<DocumentSummary> original = documentRepository.findActiveByWorkspaceAndSha256(workspaceId, sha256);
        String status = original.isPresent()
                ? DocumentStatus.DUPLICATE.name()
                : DocumentStatus.PENDING.name();
        Long duplicateOf = original.map(DocumentSummary::id).orElse(null);

        long id = documentRepository.insert(workspaceId, fileName, sourcePath, sha256,
                fileSize, mimeType, DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                status, duplicateOf, parentVersionDocumentId);
        return new RegistrationResult(id, status, duplicateOf);
    }

    public record RegistrationResult(long documentId, String status, Long duplicateOfDocumentId) {
    }
}
