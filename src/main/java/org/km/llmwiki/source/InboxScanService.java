package org.km.llmwiki.source;

import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class InboxScanService {

    private static final String INBOX_PREFIX = "inbox/";

    private final WorkspaceService workspaceService;
    private final DocumentRepository documentRepository;
    private final DocumentRegistrationService registrationService;

    public InboxScanService(WorkspaceService workspaceService, DocumentRepository documentRepository,
                            DocumentRegistrationService registrationService) {
        this.workspaceService = workspaceService;
        this.documentRepository = documentRepository;
        this.registrationService = registrationService;
    }

    public RescanResponse rescan() {
        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
        Path root = Path.of(workspace.rootPath());
        Path inbox = Path.of(workspace.inboxPath());

        int existing = 0;
        int duplicates = 0;
        int newDocuments = 0;

        for (Path file : listRegularFiles(inbox)) {
            String relativePath = INBOX_PREFIX + inbox.relativize(file).normalize().toString().replace('\\', '/');
            String sha256 = sha256Of(file);

            Optional<DocumentSummary> current =
                    documentRepository.findActiveByWorkspaceAndSourcePath(workspace.id(), relativePath);
            if (current.isPresent() && current.get().sha256().equals(sha256)) {
                existing++;
                continue;
            }

            try {
                DocumentRegistrationService.RegistrationResult result = current.isPresent()
                        ? registrationService.replaceVersion(workspace.id(), current.get(),
                                file.getFileName().toString(), relativePath, sha256,
                                Files.size(file), probeMimeType(file))
                        : registerDocument(workspace.id(), file, relativePath, sha256, null);
                if (DocumentStatus.DUPLICATE.name().equals(result.status())) {
                    duplicates++;
                } else {
                    newDocuments++;
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Could not register scanned document: " + file, exception);
            }
        }

        int removed = 0;
        for (DocumentSummary summary : documentRepository.findInboxManaged(workspace.id())) {
            if (!Files.exists(root.resolve(summary.sourcePath()))) {
                documentRepository.markDeleted(summary.id());
                removed++;
            }
        }

        return new RescanResponse(newDocuments, duplicates, existing, removed);
    }

    private DocumentRegistrationService.RegistrationResult registerDocument(
            long workspaceId, Path file, String sourcePath, String sha256, Long parentVersionId) {
        try {
            return registrationService.register(workspaceId, file.getFileName().toString(), sourcePath,
                    sha256, Files.size(file), probeMimeType(file), parentVersionId);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not register scanned document: " + file, exception);
        }
    }

    private static List<Path> listRegularFiles(Path inbox) {
        if (!Files.isDirectory(inbox)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.walk(inbox)) {
            Path inboxRealPath = inbox.toRealPath();
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> withinInboxBoundary(path, inboxRealPath))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not scan inbox directory: " + inbox, exception);
        }
    }

    private static boolean withinInboxBoundary(Path path, Path inboxRealPath) {
        if (Files.isSymbolicLink(path)) {
            return false;
        }
        try {
            return path.toRealPath().startsWith(inboxRealPath);
        } catch (IOException exception) {
            return false;
        }
    }

    private static String sha256Of(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not hash scanned document: " + file, exception);
        }
    }

    private static String probeMimeType(Path file) {
        try {
            return Files.probeContentType(file);
        } catch (IOException exception) {
            return null;
        }
    }
}
