package org.km.llmwiki.search;

import org.km.llmwiki.web.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public PageResponse<List<SearchResult>> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String corpus,
            @RequestParam(required = false) String pageType,
            @RequestParam(required = false) Long documentId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return searchService.search(query, corpus, pageType, documentId, page, size);
    }
}
