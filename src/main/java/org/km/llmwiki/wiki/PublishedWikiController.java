package org.km.llmwiki.wiki;

import org.km.llmwiki.web.ApiResponse;
import org.km.llmwiki.web.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only Published Wiki consumption surface (#373). Every response is a backend
 * authority projection of the workspace-scoped PUBLISHED knowledge_page rows with
 * hash-validated canonical content; the Browser never decides publish state itself.
 */
@RestController
@RequestMapping("/api/v1/wiki")
public class PublishedWikiController {

    private final PublishedWikiReadService readService;

    public PublishedWikiController(PublishedWikiReadService readService) {
        this.readService = readService;
    }

    @GetMapping
    public PageResponse<List<PublishedWikiSummary>> list(
            @RequestParam(required = false) String pageType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return readService.list(pageType, page, size);
    }

    @GetMapping("/{knowledgeId}")
    public ApiResponse<PublishedWikiPage> read(@PathVariable String knowledgeId) {
        return new ApiResponse<>(readService.read(knowledgeId));
    }
}
