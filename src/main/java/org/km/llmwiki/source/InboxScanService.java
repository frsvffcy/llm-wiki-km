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
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class InboxScanService {

    private static final String INBOX_PREFIX = "inbox/";

    private final WorkspaceService workspaceService;
    private final DocumentRepository documentRepository;

    public InboxScanService(WorkspaceService workspaceService, DocumentRepository documentRepository) {
        this.workspaceService = workspaceService;
        this.documentRepository = documentRepository;
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

            Optional<DocumentSummary> registered =
                    documentRepository.findActiveByWorkspaceAndSourcePath(workspace.id(), relativePath);
            if (registered.isPresent()) {
                if (registered.get().sha256().equals(sha256)) {
                    existing++;
                } else {
                    newDocuments += registerDocument(workspace.id(), file, relativePath, sha256);
                }
                continue;
            }

            if (documentRepository.existsByWorkspaceAndSha256(workspace.id(), sha256)) {
                duplicates++;
                continue;
            }
            newDocuments += registerDocument(workspace.id(), file, relativePath, sha256);
        }

        int removed = 0;
        for (DocumentSummary summary : documentRepository.findInboxPending(workspace.id())) {
            if (!Files.exists(root.resolve(summary.sourcePath()))) {
                documentRepository.markDeleted(summary.id());
                removed++;
            }
        }

        return new RescanResponse(newDocuments, duplicates, existing, removed);
    }

    private int registerDocument(long workspaceId, Path file, String sourcePath, String sha256) {
        try {
            documentRepository.insert(workspaceId, file.getFileName().toString(), sourcePath, sha256,
                    Files.size(file), probeMimeType(file),
                    DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not register scanned document: " + file, exception);
        }
        return 1;
    }

    private static List<Path> listRegularFiles(Path inbox) {
        if (!Files.isDirectory(inbox)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.walk(inbox)) {
            return entries.filter(Files::isRegularFile).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not scan inbox directory: " + inbox, exception);
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
