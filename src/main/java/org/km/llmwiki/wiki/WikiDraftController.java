package org.km.llmwiki.wiki;

import org.km.llmwiki.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Synchronous review APIs for persisted Wiki Draft metadata, preview, and diff. */
@RestController
@RequestMapping("/api/v1/wiki-drafts")
public class WikiDraftController {

    private final WikiDraftPersistenceService service;
    private final WikiDraftCreationService creationService;
    private final WikiPublishService publishService;

    public WikiDraftController(WikiDraftPersistenceService service, WikiDraftCreationService creationService,
                               WikiPublishService publishService) {
        this.service = service;
        this.creationService = creationService;
        this.publishService = publishService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WikiDraftResponse>> create(@RequestBody CreateWikiDraftRequest request) {
        if (request == null || request.proposalId() <= 0) {
            throw new IllegalArgumentException("proposalId must be positive");
        }
        // #601：與 auto-draft 共用 atomic create-or-reuse（衝突沿用，不重複建立）；
        // 沿用既有草稿回 200，只有真正新建回 201（與 publish 端點同例）。
        WikiDraftCreationService.CreatedDraft result = creationService.createOrReuse(request.proposalId());
        HttpStatus status = result.reused() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(new ApiResponse<>(result.response()));
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
    public ResponseEntity<ApiResponse<WikiDraftResponse>> regenerate(@PathVariable long draftId) {
        WikiDraftCreationService.CreatedDraft result = creationService.regenerate(draftId);
        HttpStatus status = result.reused() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(new ApiResponse<>(result.response()));
    }

    @PostMapping("/{draftId}/publish")
    public ResponseEntity<ApiResponse<WikiPublishResult>> publish(@PathVariable long draftId) {
        WikiPublishResult response = publishService.publish(draftId);
        HttpStatus status = response.result() == WikiPublishResultType.PUBLISHED
                && response.outcome() == WikiPublishOutcome.CREATED
                ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(new ApiResponse<>(response));
    }
}
