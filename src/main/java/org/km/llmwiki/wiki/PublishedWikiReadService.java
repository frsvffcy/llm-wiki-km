package org.km.llmwiki.wiki;

import org.km.llmwiki.web.PageResponse;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Read-only Browser projection of Published Wiki. Authoritative state stays in the
 * knowledge_page table and the canonical vault bytes: every read is workspace-scoped,
 * PUBLISHED-only, and hash-validated by {@link PublishedWikiContentReader} before any
 * content leaves the backend. The list surface is a bounded personal-scale projection
 * (in-memory filter/pagination over the workspace's published set, response size
 * clamped to the API-wide 1..200 limit).
 */
@Service
public class PublishedWikiReadService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final WorkspaceService workspaceService;
    private final PublishedWikiRepository repository;
    private final PublishedWikiContentReader contentReader;

    public PublishedWikiReadService(WorkspaceService workspaceService,
                                    PublishedWikiRepository repository,
                                    PublishedWikiContentReader contentReader) {
        this.workspaceService = workspaceService;
        this.repository = repository;
        this.contentReader = contentReader;
    }

    public PageResponse<List<PublishedWikiSummary>> list(String rawPageType, Integer page, Integer size) {
        int pageNumber = requirePage(page);
        int pageSize = requireSize(size);
        WikiPageType pageType = requirePageType(rawPageType);
        WorkspaceResponse workspace = activeWorkspace();
        List<PublishedWikiSummary> all = repository.findAllPublished(workspace.id()).stream()
                .filter(candidate -> pageType == null || candidate.pageType() == pageType)
                .map(PublishedWikiSummary::from)
                .toList();
        int from = Math.min(pageNumber * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return PageResponse.of(all.subList(from, to), pageNumber, pageSize, all.size());
    }

    public PublishedWikiPage read(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            throw new IllegalArgumentException("knowledgeId is required");
        }
        WorkspaceResponse workspace = activeWorkspace();
        StoredPublishedWiki page = repository
                .findPublishedByKnowledgeId(workspace.id(), knowledgeId)
                .orElseThrow(() -> new WikiPageNotFoundException(knowledgeId));
        return PublishedWikiPage.of(page, contentReader.readSearchableContent(page));
    }

    private int requirePage(Integer page) {
        if (page != null && page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        return page == null ? 0 : page;
    }

    private int requireSize(Integer size) {
        if (size != null && (size < 1 || size > MAX_PAGE_SIZE)) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return size == null ? DEFAULT_PAGE_SIZE : size;
    }

    private WikiPageType requirePageType(String rawPageType) {
        if (rawPageType == null || rawPageType.isBlank()) {
            return null;
        }
        try {
            return WikiPageType.valueOf(rawPageType);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("unknown pageType: " + rawPageType);
        }
    }

    private WorkspaceResponse activeWorkspace() {
        return workspaceService.findActiveWithoutValidation().orElseThrow(NoActiveWorkspaceException::new);
    }
}
