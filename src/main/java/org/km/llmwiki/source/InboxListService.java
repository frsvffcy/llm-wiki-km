package org.km.llmwiki.source;

import org.km.llmwiki.web.PageResponse;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class InboxListService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final Map<String, String> SORTABLE_COLUMNS = Map.of(
            "createdAt", "created_at",
            "fileName", "file_name",
            "fileSize", "file_size",
            "status", "status");

    private final WorkspaceService workspaceService;
    private final DocumentRepository documentRepository;

    public InboxListService(WorkspaceService workspaceService, DocumentRepository documentRepository) {
        this.workspaceService = workspaceService;
        this.documentRepository = documentRepository;
    }

    public PageResponse<List<InboxDocumentRow>> list(String status, String extension,
                                                     String sort, Integer page, Integer size) {
        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);

        String statusFilter = validateStatus(status);
        String extensionFilter = normalizeExtension(extension);
        String orderBy = resolveOrderBy(sort);
        int pageNumber = requiredPage(page);
        int pageSize = boundedSize(size);

        long totalElements = documentRepository.countInboxDocuments(workspace.id(), statusFilter, extensionFilter);
        List<InboxDocumentRow> items = totalElements == 0
                ? List.of()
                : documentRepository.findInboxDocuments(workspace.id(), statusFilter, extensionFilter,
                        orderBy, pageSize, pageNumber * pageSize);

        return PageResponse.of(items, pageNumber, pageSize, totalElements);
    }

    public Optional<InboxDocumentRow> getInboxDocument(long documentId) {
        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
        return documentRepository.findInboxDocument(workspace.id(), documentId);
    }

    private static String validateStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        try {
            return DocumentStatus.valueOf(normalized).name();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown status filter: " + status);
        }
    }

    static String normalizeExtension(String extension) {
        if (extension == null) {
            return null;
        }
        String normalized = extension.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private static String resolveOrderBy(String sort) {
        if (sort == null || sort.isBlank()) {
            return "created_at DESC";
        }
        String[] parts = sort.split(",", 2);
        String columnKey = parts[0].trim();
        String column = SORTABLE_COLUMNS.get(columnKey);
        if (column == null) {
            throw new IllegalArgumentException("Unsupported sort field: " + columnKey
                    + ", allowed: " + SORTABLE_COLUMNS.keySet());
        }
        String direction = "asc";
        if (parts.length > 1 && !parts[1].isBlank()) {
            direction = parts[1].trim().toLowerCase(Locale.ROOT);
            if (!direction.equals("asc") && !direction.equals("desc")) {
                throw new IllegalArgumentException("Unsupported sort direction: " + parts[1]);
            }
        }
        return column + " " + direction.toUpperCase(Locale.ROOT);
    }

    private static int requiredPage(Integer page) {
        if (page != null && page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        return page == null ? 0 : page;
    }

    private static int boundedSize(Integer size) {
        int resolved = size == null ? 20 : size;
        if (resolved < 1) {
            throw new IllegalArgumentException("size must be >= 1");
        }
        if (resolved > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be <= " + MAX_PAGE_SIZE);
        }
        return resolved;
    }
}
