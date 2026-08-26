package com.frsvffcy.llmwiki.workspace;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class WorkspaceService {

    private static final List<String> DIRECTORY_NAMES = List.of(
            "inbox", "archive", "vault", "data", "config", "logs", "temp");

    private final WorkspaceRepository repository;

    public WorkspaceService(WorkspaceRepository repository) {
        this.repository = repository;
    }

    public WorkspaceResponse create(CreateWorkspaceRequest request) {
        String name = requireNonBlank(request.name(), "name must not be blank");
        Path root = validateRootPath(request.rootPath());

        repository.findIdByRootPath(root.toString()).ifPresent(existingId -> {
            throw new DuplicateWorkspaceException(root.toString(), existingId);
        });

        createDirectoryLayout(root);
        initializeKnowledgeDatabase(root.resolve("data").resolve("knowledge.db"));

        Instant now = Instant.now();
        WorkspaceRecord record = new WorkspaceRecord(
                name,
                root.toString(),
                root.resolve("inbox").toString(),
                root.resolve("archive").toString(),
                root.resolve("vault").toString(),
                root.resolve("data").toString(),
                root.resolve("config").toString(),
                "ACTIVE",
                DateTimeFormatter.ISO_INSTANT.format(now),
                DateTimeFormatter.ISO_INSTANT.format(now));

        long id = repository.insert(record);
        return toResponse(id, record);
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static Path validateRootPath(String rawRootPath) {
        if (rawRootPath == null || rawRootPath.isBlank()) {
            throw new IllegalArgumentException("rootPath must not be blank");
        }
        Path root = Path.of(rawRootPath).normalize();
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("rootPath must be an absolute path");
        }
        Path filesystemRoot = root.getRoot();
        if (filesystemRoot != null && root.equals(filesystemRoot)) {
            throw new IllegalArgumentException("rootPath must not be the filesystem root");
        }
        if (Files.exists(root) && !Files.isDirectory(root)) {
            throw new IllegalArgumentException("rootPath exists and is not a directory: " + root);
        }
        return root;
    }

    private static void createDirectoryLayout(Path root) {
        for (String directoryName : DIRECTORY_NAMES) {
            try {
                Files.createDirectories(root.resolve(directoryName));
            } catch (IOException exception) {
                throw new IllegalStateException("Could not create workspace directory: "
                        + root.resolve(directoryName), exception);
            }
        }
    }

    private static void initializeKnowledgeDatabase(Path databasePath) {
        if (Files.exists(databasePath)) {
            return;
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            connection.createStatement().execute("SELECT 1");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize knowledge database: " + databasePath, exception);
        }
    }

    private static WorkspaceResponse toResponse(long id, WorkspaceRecord record) {
        return new WorkspaceResponse(
                id,
                record.name(),
                record.rootPath(),
                record.inboxPath(),
                record.archivePath(),
                record.vaultPath(),
                record.dataPath(),
                record.configPath(),
                record.status(),
                record.createdAt(),
                record.updatedAt());
    }
}
