package org.km.llmwiki.workspace;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class WorkspaceService {

    private final WorkspaceRepository repository;
    private final WorkspaceLayoutValidator validator;

    public WorkspaceService(WorkspaceRepository repository, WorkspaceLayoutValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    public WorkspaceResponse create(CreateWorkspaceRequest request) {
        String name = requireNonBlank(request.name(), "name must not be blank");
        Path root = validateRootPath(request.rootPath());

        repository.findIdByRootPath(root.toString()).ifPresent(existingId -> {
            throw new DuplicateWorkspaceException(root.toString(), existingId);
        });

        createDirectoryLayout(root);
        createDirectoryLayout(root);

        Instant now = Instant.now();
        WorkspaceRecord record = new WorkspaceRecord(
                name,
                root.toString(),
                root.resolve("inbox").toString(),
                root.resolve("archive").toString(),
                root.resolve("vault").toString(),
                root.resolve("data").toString(),
                root.resolve("config").toString(),
                "INACTIVE",
                DateTimeFormatter.ISO_INSTANT.format(now),
                DateTimeFormatter.ISO_INSTANT.format(now));

        long id = repository.insert(record);
        repository.activate(id);
        return get(id);
    }

    public List<WorkspaceResponse> list() {
        return repository.findAll().stream().map(WorkspaceRow::toResponse).toList();
    }

    public WorkspaceResponse get(long id) {
        return requireRow(id).toResponse();
    }

    public WorkspaceStatusResponse current() {
        WorkspaceRow row = repository.findActive().orElseThrow(NoActiveWorkspaceException::new);
        return statusOf(row);
    }

    public WorkspaceStatusResponse open(long id) {
        requireRow(id);
        repository.activate(id);
        return statusOf(requireRow(id));
    }

    public Optional<WorkspaceResponse> findActiveWithoutValidation() {
        return repository.findActive().map(WorkspaceRow::toResponse);
    }

    private WorkspaceStatusResponse statusOf(WorkspaceRow row) {
        WorkspaceLayoutValidator.LayoutReport report =
                validator.validateAndRepair(Path.of(row.rootPath()));
        return new WorkspaceStatusResponse(row.toResponse(), report);
    }

    private WorkspaceRow requireRow(long id) {
        return repository.findById(id).orElseThrow(() -> new WorkspaceNotFoundException(id));
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
        for (String directoryName : WorkspaceLayoutValidator.DIRECTORY_NAMES) {
            try {
                Files.createDirectories(root.resolve(directoryName));
            } catch (IOException exception) {
                throw new IllegalStateException("Could not create workspace directory: "
                        + root.resolve(directoryName), exception);
            }
        }
    }
}
