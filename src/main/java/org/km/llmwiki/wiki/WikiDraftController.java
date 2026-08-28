package org.km.llmwiki.wiki;

import org.km.llmwiki.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Synchronous review APIs for persisted Wiki Draft metadata, preview, and diff. */
@RestController
@RequestMapping("/api/v1/wiki-drafts")
public class WikiDraftController {

    private final WikiDraftPersistenceService service;

    public WikiDraftController(WikiDraftPersistenceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WikiDraftResponse> create(@RequestBody CreateWikiDraftRequest request) {
        return new ApiResponse<>(service.create(request));
    }

    @GetMapping("/{draftId}")
    public ApiResponse<WikiDraftResponse> get(@PathVariable long draftId) {
        return new ApiResponse<>(service.get(draftId));
    }

    @GetMapping("/{draftId}/preview")
    public ApiResponse<WikiDraftPreviewResponse> preview(@PathVariable long draftId) {
        return new ApiResponse<>(service.preview(draftId));
    }

    @GetMapping("/{draftId}/diff")
    public ApiResponse<WikiDraftDiffResponse> diff(@PathVariable long draftId) {
        return new ApiResponse<>(service.diff(draftId));
    }

    @PostMapping("/{draftId}/invalidate")
    public ApiResponse<WikiDraftResponse> invalidate(@PathVariable long draftId) {
        return new ApiResponse<>(service.invalidate(draftId));
    }

    @PostMapping("/{draftId}/regenerate")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WikiDraftResponse> regenerate(@PathVariable long draftId) {
        return new ApiResponse<>(service.regenerate(draftId));
    }
}
