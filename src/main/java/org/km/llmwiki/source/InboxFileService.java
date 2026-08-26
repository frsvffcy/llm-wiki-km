package org.km.llmwiki.source;

import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

@Service
public class InboxFileService {

    private final WorkspaceService workspaceService;
    private final DocumentRepository documentRepository;

    public InboxFileService(WorkspaceService workspaceService, DocumentRepository documentRepository) {
        this.workspaceService = workspaceService;
        this.documentRepository = documentRepository;
    }

    public UploadedFileResponse upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("uploaded file must not be empty");
        }
        String fileName = sanitizeFileName(file.getOriginalFilename());

        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
        Path inbox = Path.of(workspace.inboxPath());
        Path tempDirectory = Path.of(workspace.rootPath()).resolve("temp");
        Path target = uniqueTarget(inbox, fileName);

        Path tempFile = null;
        Long documentId = null;
        try {
            tempFile = Files.createTempFile(tempDirectory, "upload-", ".tmp");
            String sha256 = copyAndDigest(file, tempFile);

            long fileSize = Files.size(tempFile);
            documentId = documentRepository.insert(
                    workspace.id(), target.getFileName().toString(),
                    "inbox/" + target.getFileName().toString(), sha256,
                    fileSize, file.getContentType(),
                    DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

            Files.createDirectories(inbox);
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            tempFile = null;

            return new UploadedFileResponse(documentId, target.getFileName().toString(), "PENDING", false);
        } catch (IOException | NoSuchAlgorithmException exception) {
            if (documentId != null) {
                documentRepository.deleteById(documentId);
            }
            throw new IllegalStateException("Could not store uploaded file", exception);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) {
            throw new IllegalArgumentException("file name must not be blank");
        }
        String name = original.trim();
        int lastSeparator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSeparator >= 0) {
            name = name.substring(lastSeparator + 1);
        }
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("file name is not a valid document name: " + original);
        }
        if (name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("file name contains illegal characters");
        }
        return name;
    }

    private static Path uniqueTarget(Path inbox, String fileName) {
        Path candidate = inbox.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String base = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        for (int sequence = 1; sequence < 10_000; sequence++) {
            Path numbered = inbox.resolve(base + "-" + sequence + extension);
            if (!Files.exists(numbered)) {
                return numbered;
            }
        }
        throw new IllegalStateException("Could not find a free file name in inbox for: " + fileName);
    }

    private static String copyAndDigest(MultipartFile file, Path destination)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = file.getInputStream();
             DigestInputStream digesting = new DigestInputStream(input, digest)) {
            Files.copy(digesting, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
