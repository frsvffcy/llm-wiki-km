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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
public class InboxFileService {

    private static final int MAX_NAME_ALLOCATION_ATTEMPTS = 10_000;

    private final WorkspaceService workspaceService;
    private final DocumentRegistrationService registrationService;

    public InboxFileService(WorkspaceService workspaceService,
                            DocumentRegistrationService registrationService) {
        this.workspaceService = workspaceService;
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
