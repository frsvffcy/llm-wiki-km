package org.km.llmwiki.source;

import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
public class InboxFileService {

    private static final int MAX_NAME_ALLOCATION_ATTEMPTS = 10_000;
    private static final Set<String> DELETABLE_STATUSES = java.util.Set.of(
            DocumentStatus.PENDING.name(),
            DocumentStatus.FAILED.name(),
            DocumentStatus.DUPLICATE.name(),
            DocumentStatus.UNSUPPORTED.name(),
            DocumentStatus.NEED_OCR.name());

    private final WorkspaceService workspaceService;
    private final DocumentRepository documentRepository;
    private final DocumentRegistrationService registrationService;

    public InboxFileService(WorkspaceService workspaceService, DocumentRepository documentRepository,
                            DocumentRegistrationService registrationService) {
        this.workspaceService = workspaceService;
        this.documentRepository = documentRepository;
        this.registrationService = registrationService;
    }

    public BatchUploadResponse uploadAll(List<MultipartFile> files) {
        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
        List<UploadedFileResponse> documents = new ArrayList<>();
        List<BatchUploadResponse.FailedFile> failures = new ArrayList<>();

        for (MultipartFile file : files) {
            String fileName = file == null ? "" : String.valueOf(file.getOriginalFilename());
            try {
                if (file == null || file.isEmpty()) {
                    throw new IllegalArgumentException("uploaded file must not be empty");
                }
                documents.add(uploadIn(workspace, sanitizeFileName(fileName), file));
            } catch (RuntimeException exception) {
                failures.add(new BatchUploadResponse.FailedFile(fileName, exception.getMessage()));
            }
        }

        long duplicates = documents.stream().filter(UploadedFileResponse::duplicate).count();
        return new BatchUploadResponse(files.size(), documents.size(), (int) duplicates, failures.size(),
                List.copyOf(documents), List.copyOf(failures));
    }

    public UploadedFileResponse upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("uploaded file must not be empty");
        }
        String fileName = sanitizeFileName(file.getOriginalFilename());
        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
        return uploadIn(workspace, fileName, file);
    }

    private UploadedFileResponse uploadIn(WorkspaceResponse workspace, String fileName, MultipartFile file) {
        Path inbox = Path.of(workspace.inboxPath());
        Path stored = null;
        try (InputStream input = file.getInputStream()) {
            WrittenFile written = writeToInbox(input, inbox, fileName);
            stored = written.path();

            DocumentRegistrationService.RegistrationResult registration;
            try {
                registration = registrationService.register(
                        workspace.id(), fileName, stored.getFileName().toString(),
                        "inbox/" + stored.getFileName().toString(), written.sha256(),
                        written.size(), file.getContentType(), null);
            } catch (RuntimeException exception) {
                Files.deleteIfExists(stored);
                stored = null;
                throw exception;
            }

            boolean duplicate = "DUPLICATE".equals(registration.status());
            return new UploadedFileResponse(registration.documentId(), stored.getFileName().toString(),
                    registration.status(), duplicate);
        } catch (IOException | NoSuchAlgorithmException exception) {
            if (stored != null) {
                try {
                    Files.deleteIfExists(stored);
                } catch (IOException ignored) {
                }
            }
            throw new IllegalStateException("Could not store uploaded file", exception);
        }
    }


    /**
     * Consistency model: the physical file is first moved (atomic rename) into the workspace
     * {@code temp/} staging directory; only then is the database row soft-deleted. If the database
     * update fails, the staged file is restored to its original inbox location so no state change
     * survives. After a successful commit the staged copy is discarded. A crash between the move
     * and the database update leaves at most an unreferenced file under {@code temp/}; a later
     * rescan reconciles the missing-inbox state through its removed-document detection.
     */
    @org.springframework.transaction.annotation.Transactional
    public void delete(long documentId) {
        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
        DocumentDeletionView document = documentRepository.findDeletionView(workspace.id(), documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        if (!DELETABLE_STATUSES.contains(document.status())) {
            throw new DocumentAlreadyProcessedException(documentId, document.status());
        }

        Path lexicalTarget = Path.of(workspace.rootPath()).toAbsolutePath().normalize()
                .resolve(document.sourcePath()).normalize();
        if (Files.isSymbolicLink(lexicalTarget)) {
            throw new IllegalArgumentException(
                    "document path is a symbolic link: " + document.sourcePath());
        }

        Path targetParentReal;
        try {
            targetParentReal = lexicalTarget.getParent().toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "document path parent does not resolve inside the workspace inbox: "
                            + document.sourcePath(), exception);
        }
        Path inboxRealPath = inboxRealPath(workspace);
        if (!targetParentReal.startsWith(inboxRealPath)) {
            throw new IllegalArgumentException(
                    "document path escapes the workspace inbox: " + document.sourcePath());
        }
        Path target = targetParentReal.resolve(lexicalTarget.getFileName());

        boolean wasCanonical = DocumentStatus.PENDING.name().equals(document.status());

        Path stagingDirectory = Path.of(workspace.rootPath()).resolve("temp");
        Path staged;
        try {
            Files.createDirectories(stagingDirectory);
            Path stagingTarget = stagingDirectory.resolve(
                    "delete-" + java.util.UUID.randomUUID() + "-" + target.getFileName());
            staged = moveToStaging(target, stagingTarget);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not stage inbox file for deletion", exception);
        }

        try {
            documentRepository.markDeleted(documentId);
        } catch (RuntimeException databaseFailure) {
            restoreFromStaging(staged, target, databaseFailure);
            throw databaseFailure;
        }

        try {
            Files.deleteIfExists(staged);
        } catch (IOException ignored) {
        }

        if (wasCanonical) {
            promoteSuccessorFor(workspace.id(), documentId,
                    Path.of(workspace.rootPath()).toAbsolutePath().normalize());
        }
    }

    private void promoteSuccessorFor(long workspaceId, long deletedCanonicalId, Path root) {
        List<DocumentSummary> duplicates =
                documentRepository.findCurrentDuplicatesOf(workspaceId, deletedCanonicalId);
        if (duplicates.isEmpty()) {
            return;
        }
        DocumentSummary successor = duplicates.stream()
                .filter(candidate -> Files.exists(root.resolve(candidate.sourcePath())))
                .findFirst()
                .orElse(duplicates.get(0));
        documentRepository.promoteDuplicateToCanonical(successor.id());
        documentRepository.repointDuplicates(deletedCanonicalId, successor.id());
    }

    private static Path moveToStaging(Path target, Path stagingTarget) throws IOException {
        try {
            Files.move(target, stagingTarget, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(target, stagingTarget);
        }
        return stagingTarget;
    }

    private static void restoreFromStaging(Path staged, Path originalLocation, RuntimeException cause) {
        try {
            Files.createDirectories(originalLocation.getParent());
            Files.move(staged, originalLocation, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException restoreFailure) {
            IllegalStateException unrecoverable = new IllegalStateException(
                    "Delete compensation failed: staged file could not be restored to "
                            + originalLocation + "; staged copy remains at " + staged,
                    restoreFailure);
            unrecoverable.addSuppressed(cause);
            throw unrecoverable;
        }
    }

    private static Path inboxRealPath(WorkspaceResponse workspace) {
        try {
            return Path.of(workspace.inboxPath()).toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not resolve workspace inbox directory", exception);
        }
    }

    private static WrittenFile writeToInbox(InputStream input, Path inbox, String fileName)
            throws IOException, NoSuchAlgorithmException {
        Files.createDirectories(inbox);
        String base = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        for (int attempt = 0; attempt < MAX_NAME_ALLOCATION_ATTEMPTS; attempt++) {
            String candidateName = attempt == 0 ? fileName : base + "-" + attempt + extension;
            Path candidate = inbox.resolve(candidateName);
            try (FileChannel channel = FileChannel.open(candidate,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                return transferWithDigest(input, channel, candidate);
            } catch (FileAlreadyExistsException ignored) {
            } catch (IOException exception) {
                Files.deleteIfExists(candidate);
                throw exception;
            }
        }
        throw new IllegalStateException("Could not allocate a free inbox slot for: " + fileName);
    }

    private static WrittenFile transferWithDigest(InputStream input, FileChannel channel, Path target)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream digesting = new DigestInputStream(input, digest)) {
            byte[] buffer = new byte[8192];
            long size = 0;
            while (true) {
                int read = digesting.read(buffer);
                if (read < 0) {
                    break;
                }
                ByteBuffer wrapped = ByteBuffer.wrap(buffer, 0, read);
                while (wrapped.hasRemaining()) {
                    size += channel.write(wrapped);
                }
            }
            return new WrittenFile(target, HexFormat.of().formatHex(digest.digest()), size);
        }
    }

    private record WrittenFile(Path path, String sha256, long size) {
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
}
